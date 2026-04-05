package com.xenombrowser

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    // ─── Two simple modes ─────────────────────────────────────────────────────
    // SCROLL   – arrows scroll the page, OK enters PICK
    // PICK     – arrows move between links (JS highlight), OK clicks, BACK → SCROLL
    private enum class Mode { SCROLL, PICK }
    private var mode = Mode.SCROLL

    // ─── Views ────────────────────────────────────────────────────────────────
    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnBookmarkAdd: ImageButton
    private lateinit var btnBookmarks: ImageButton
    private lateinit var panelBookmarks: LinearLayout
    private lateinit var rvBookmarks: RecyclerView

    // ─── Misc ─────────────────────────────────────────────────────────────────
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var skipChecker: Runnable? = null
    private val adDomains = mutableSetOf<String>()
    private val videoExtensions = listOf(
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv",
        ".webm", ".m3u8", ".m3u", ".ts", ".mpd"
    )

    // ─── onCreate ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadAdDomains()

        webView        = findViewById(R.id.web_view)
        etUrl          = findViewById(R.id.et_url)
        tvStatus       = findViewById(R.id.tv_status)
        btnBack        = findViewById(R.id.btn_back)
        btnForward     = findViewById(R.id.btn_forward)
        btnBookmarkAdd = findViewById(R.id.btn_bookmark_add)
        btnBookmarks   = findViewById(R.id.btn_bookmarks)
        panelBookmarks = findViewById(R.id.panel_bookmarks)
        rvBookmarks    = findViewById(R.id.rv_bookmarks)

        setupWebView()
        setupUrlBar()
        setupButtons()
        setupBookmarksList()
        startSkipChecker()

        webView.loadUrl("https://ya.ru")
        hint("ОК — выбор ссылок   ↑ из верха страницы — строка поиска")
    }

    // ─── WebView ──────────────────────────────────────────────────────────────

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = false
            // Desktop UA so Yandex serves full desktop layout
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        WebView.setWebContentsDebuggingEnabled(false)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView, req: WebResourceRequest
            ): WebResourceResponse? {
                val host = req.url.host ?: return null
                if (adDomains.any { host == it || host.endsWith(".$it") })
                    return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, req: WebResourceRequest
            ): Boolean {
                val url = req.url.toString()
                // Intercept direct video files → ExoPlayer
                if (videoExtensions.any { url.contains(it, ignoreCase = true) }) {
                    openInExoPlayer(url); return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, fav: Bitmap?) {
                etUrl.setText(url)
                updateBookmarkBtn(url)
                if (mode == Mode.PICK) exitPick()
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectJs()
                updateNavBtns()
                etUrl.setText(url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            private var cb: CustomViewCallback? = null

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view; cb = callback
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                (window.decorView as ViewGroup).addView(view)
                webView.visibility = View.GONE
                findViewById<LinearLayout>(R.id.nav_bar).visibility = View.GONE
                tvStatus.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let { (window.decorView as ViewGroup).removeView(it) }
                customView = null; cb?.onCustomViewHidden()
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                webView.visibility = View.VISIBLE
                findViewById<LinearLayout>(R.id.nav_bar).visibility = View.VISIBLE
            }
        }
    }

    // ─── JS injection ─────────────────────────────────────────────────────────

    private fun injectJs() {
        // language=JavaScript
        val js = """
        (function(){
            if (window._xb) return;

            window._xb = {
                els: [], idx: -1,

                /* Check element is visible and on screen */
                vis: function(el) {
                    var s = getComputedStyle(el);
                    if (s.display === 'none' || s.visibility === 'hidden' || parseFloat(s.opacity) < 0.1) return false;
                    var r = el.getBoundingClientRect();
                    return r.width > 2 && r.height > 2 && r.bottom > 0 && r.top < window.innerHeight;
                },

                /* Collect interactive elements — Yandex-aware */
                collect: function() {
                    var sel = [
                        /* Yandex search results */
                        '.OrganicTitle-Link',
                        '.organic__url',
                        'a.link_theme_normal',
                        '.serp-item a[href]',
                        /* Yandex inputs */
                        'input[name="text"]',
                        'input.input__control',
                        /* General */
                        'a[href]:not([href^="javascript"])',
                        'button:not([disabled])',
                        '[role="button"]',
                        'input[type="submit"]',
                        'input[type="button"]'
                    ].join(',');

                    var seen = new Set();
                    return Array.from(document.querySelectorAll(sel)).filter(function(el) {
                        if (seen.has(el)) return false;
                        seen.add(el);
                        return window._xb.vis(el);
                    });
                },

                hl: function(el) {
                    /* Remove old highlight */
                    document.querySelectorAll('[data-xb-hl]').forEach(function(e) {
                        e.style.outline = e._xbOutline || '';
                        e.style.backgroundColor = e._xbBg || '';
                        e.removeAttribute('data-xb-hl');
                    });
                    /* Apply new highlight */
                    el._xbOutline = el.style.outline || '';
                    el._xbBg = el.style.backgroundColor || '';
                    el.setAttribute('data-xb-hl', '1');
                    el.style.outline = '3px solid #00B4FF';
                    el.style.backgroundColor = 'rgba(0,180,255,0.08)';
                    el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
                    /* Return label for status */
                    return (el.textContent || el.value || el.placeholder || '').trim().substring(0, 60);
                },

                clear: function() {
                    document.querySelectorAll('[data-xb-hl]').forEach(function(e) {
                        e.style.outline = e._xbOutline || '';
                        e.style.backgroundColor = e._xbBg || '';
                        e.removeAttribute('data-xb-hl');
                    });
                    this.els = []; this.idx = -1;
                },

                enter: function() {
                    this.els = this.collect();
                    this.idx = -1;
                    return this.els.length;
                },

                move: function(dir) {
                    if (!this.els.length) this.els = this.collect();
                    if (!this.els.length) return '';
                    if (dir > 0) {
                        this.idx = (this.idx + 1) % this.els.length;
                    } else {
                        this.idx = this.idx <= 0 ? this.els.length - 1 : this.idx - 1;
                    }
                    return this.hl(this.els[this.idx]);
                },

                click: function() {
                    if (this.idx < 0 || this.idx >= this.els.length) return 'none';
                    var el = this.els[this.idx];
                    this.clear();
                    /* Focus input fields so keyboard appears */
                    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                        el.focus();
                        el.click();
                        return 'input';
                    }
                    el.click();
                    return el.href || el.textContent.trim().substring(0, 40);
                },

                /* Skip ad — only when video is actually playing */
                checkSkip: function() {
                    var playing = Array.from(document.querySelectorAll('video'))
                        .some(function(v) { return !v.paused && !v.ended && v.readyState > 2 && v.currentTime > 0.5; });
                    if (!playing) return 0;
                    var skipSel = '.ytp-skip-ad-button, [class*="skip-ad"], [class*="ad-skip"], [class*="skipAd"], [id*="skip-ad"]';
                    var btns = [];
                    try { btns = Array.from(document.querySelectorAll(skipSel)).filter(function(el){ return window._xb.vis(el); }); } catch(e) {}
                    if (!btns.length) {
                        var texts = ['пропустить', 'skip ad', 'skip ads'];
                        btns = Array.from(document.querySelectorAll('button, [role="button"]')).filter(function(el) {
                            var t = el.textContent.toLowerCase().trim();
                            return texts.some(function(s){ return t === s || t.startsWith(s); }) && window._xb.vis(el);
                        });
                    }
                    return btns.length;
                },

                clickSkip: function() {
                    var skipSel = '.ytp-skip-ad-button, [class*="skip-ad"], [class*="ad-skip"]';
                    var btns = [];
                    try { btns = Array.from(document.querySelectorAll(skipSel)).filter(function(el){ return window._xb.vis(el); }); } catch(e) {}
                    if (!btns.length) {
                        var texts = ['пропустить', 'skip ad'];
                        btns = Array.from(document.querySelectorAll('button, [role="button"]')).filter(function(el) {
                            var t = el.textContent.toLowerCase().trim();
                            return texts.some(function(s){ return t === s; }) && window._xb.vis(el);
                        });
                    }
                    if (btns.length) { btns[0].click(); return true; }
                    return false;
                }
            };
        })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ─── Pick mode ────────────────────────────────────────────────────────────

    private fun enterPick() {
        webView.evaluateJavascript("window._xb ? window._xb.enter() : 0") { r ->
            val n = r?.trim()?.toIntOrNull() ?: 0
            if (n > 0) {
                mode = Mode.PICK
                hint("Выбор ссылки: ↑↓ — навигация   ОК — открыть   НАЗАД — отмена  ($n)")
                // Move to first element immediately
                webView.evaluateJavascript("window._xb.move(1)") { label ->
                    val lbl = label?.trim()?.removeSurrounding("\"") ?: ""
                    if (lbl.isNotEmpty()) hint("▶ $lbl")
                }
            } else {
                hint("Кликабельных элементов не найдено")
                handler.postDelayed({ hint("ОК — выбор ссылок   ↑ из верха — строка поиска") }, 2000)
            }
        }
    }

    private fun exitPick() {
        mode = Mode.SCROLL
        webView.evaluateJavascript("if(window._xb) window._xb.clear()", null)
        hint("ОК — выбор ссылок   ↑ из верха страницы — строка поиска")
    }

    // ─── URL bar ──────────────────────────────────────────────────────────────

    private fun focusUrlBar() {
        etUrl.requestFocus()
        etUrl.selectAll()
        // Force keyboard to appear
        etUrl.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etUrl, InputMethodManager.SHOW_FORCED)
        }
        hint("Введите запрос или адрес сайта, затем нажмите ОК/Enter")
    }

    private fun setupUrlBar() {
        etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                etUrl.post { imm.showSoftInput(etUrl, InputMethodManager.SHOW_FORCED) }
            }
        }
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE) {
                navigate(etUrl.text.toString().trim())
                dismissKeyboard()
                true
            } else false
        }
    }

    private fun navigate(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://yandex.ru/search/?text=${android.net.Uri.encode(input)}&lr=213"
        }
        webView.loadUrl(url)
        hint("ОК — выбор ссылок   ↑ из верха страницы — строка поиска")
    }

    private fun dismissKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUrl.windowToken, 0)
        webView.requestFocus()
    }

    // ─── Key dispatch ─────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        // URL bar focused — let it handle everything except DOWN/BACK
        if (etUrl.isFocused) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { dismissKeyboard(); return true }
                    KeyEvent.KEYCODE_BACK -> { dismissKeyboard(); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        navigate(etUrl.text.toString().trim())
                        dismissKeyboard()
                        return true
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        // Bookmarks panel
        if (panelBookmarks.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) {
                panelBookmarks.visibility = View.GONE; return true
            }
            return super.dispatchKeyEvent(event)
        }

        return when (code) {

            KeyEvent.KEYCODE_DPAD_UP -> {
                when (mode) {
                    Mode.SCROLL -> {
                        if (webView.scrollY <= 0) {
                            focusUrlBar()
                        } else {
                            webView.scrollBy(0, -300)
                        }
                        true
                    }
                    Mode.PICK -> {
                        webView.evaluateJavascript("window._xb.move(-1)") { label ->
                            val lbl = label?.trim()?.removeSurrounding("\"") ?: ""
                            if (lbl.isNotEmpty()) hint("▶ $lbl")
                        }
                        true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (mode) {
                    Mode.SCROLL -> { webView.scrollBy(0, 300); true }
                    Mode.PICK -> {
                        webView.evaluateJavascript("window._xb.move(1)") { label ->
                            val lbl = label?.trim()?.removeSurrounding("\"") ?: ""
                            if (lbl.isNotEmpty()) hint("▶ $lbl")
                        }
                        true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (mode == Mode.SCROLL) webView.scrollBy(-300, 0)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (mode == Mode.SCROLL) webView.scrollBy(300, 0)
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (mode) {
                    Mode.SCROLL -> {
                        // Check skip buttons first (only fires when video playing)
                        webView.evaluateJavascript("window._xb ? window._xb.checkSkip() : 0") { r ->
                            if ((r?.trim()?.toIntOrNull() ?: 0) > 0) {
                                webView.evaluateJavascript("window._xb.clickSkip()", null)
                                hint("Реклама пропущена")
                            } else {
                                enterPick()
                            }
                        }
                        true
                    }
                    Mode.PICK -> {
                        webView.evaluateJavascript("window._xb.click()") { result ->
                            val r = result?.trim()?.removeSurrounding("\"") ?: ""
                            if (r == "input") {
                                // Clicked an input — focus URL bar logic doesn't apply,
                                // the WebView input field will handle keyboard via onShowCustomView
                                // or we just exit pick mode
                                mode = Mode.SCROLL
                                hint("Введите текст в поле")
                            } else {
                                mode = Mode.SCROLL
                            }
                        }
                        true
                    }
                }
            }

            KeyEvent.KEYCODE_BACK -> {
                when (mode) {
                    Mode.PICK -> { exitPick(); true }
                    Mode.SCROLL -> {
                        when {
                            webView.canGoBack() -> { webView.goBack(); true }
                            else -> { finish(); true }
                        }
                    }
                }
            }

            else -> super.dispatchKeyEvent(event)
        }
    }

    // ─── Skip checker ─────────────────────────────────────────────────────────

    private fun startSkipChecker() {
        skipChecker = object : Runnable {
            override fun run() {
                webView.evaluateJavascript("window._xb ? window._xb.checkSkip() : 0") { r ->
                    if ((r?.trim()?.toIntOrNull() ?: 0) > 0 && mode == Mode.SCROLL) {
                        hint("Реклама — нажмите ОК для пропуска")
                    }
                }
                handler.postDelayed(this, 1500)
            }
        }
        handler.postDelayed(skipChecker!!, 1500)
    }

    // ─── Buttons ──────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        btnBookmarkAdd.setOnClickListener {
            val url = webView.url ?: return@setOnClickListener
            if (BookmarkManager.contains(this, url)) {
                BookmarkManager.remove(this, url)
                toast(getString(R.string.bookmark_removed))
            } else {
                BookmarkManager.add(this, webView.title ?: url, url)
                toast(getString(R.string.bookmark_added))
            }
            updateBookmarkBtn(url)
            refreshBookmarks()
        }
        btnBookmarks.setOnClickListener {
            panelBookmarks.visibility =
                if (panelBookmarks.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (panelBookmarks.visibility == View.VISIBLE) refreshBookmarks()
        }
    }

    // ─── Bookmarks ────────────────────────────────────────────────────────────

    private fun setupBookmarksList() {
        rvBookmarks.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        refreshBookmarks()
    }

    private fun refreshBookmarks() {
        val items = BookmarkManager.getAll(this)
        rvBookmarks.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int) =
                object : RecyclerView.ViewHolder(
                    layoutInflater.inflate(R.layout.item_bookmark, p, false)) {}
            override fun getItemCount() = items.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val bm = items[i]
                h.itemView.findViewById<TextView>(R.id.tv_bookmark_title).text = bm.title
                h.itemView.setOnClickListener {
                    webView.loadUrl(bm.url)
                    panelBookmarks.visibility = View.GONE
                }
                h.itemView.findViewById<ImageButton>(R.id.btn_delete_bookmark).setOnClickListener {
                    BookmarkManager.remove(this@MainActivity, bm.url)
                    refreshBookmarks()
                }
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun hint(text: String) {
        tvStatus.text = text
        tvStatus.visibility = View.VISIBLE
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun updateBookmarkBtn(url: String) {
        btnBookmarkAdd.setImageResource(
            if (BookmarkManager.contains(this, url)) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off)
    }

    private fun updateNavBtns() {
        btnBack.alpha    = if (webView.canGoBack()) 1f else 0.4f
        btnForward.alpha = if (webView.canGoForward()) 1f else 0.4f
    }

    private fun openInExoPlayer(url: String) =
        startActivity(Intent(this, VideoActivity::class.java).apply {
            putExtra(VideoActivity.EXTRA_URL, url)
        })

    private fun loadAdDomains() {
        try {
            assets.open("adblock.txt").bufferedReader().forEachLine { line ->
                val t = line.trim()
                if (t.isNotEmpty() && !t.startsWith("#")) adDomains.add(t)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        skipChecker?.let { handler.removeCallbacks(it) }
        webView.destroy()
    }
}
