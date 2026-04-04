package com.xenombrowser

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnBookmarkAdd: ImageButton
    private lateinit var btnBookmarks: ImageButton
    private lateinit var panelBookmarks: LinearLayout
    private lateinit var rvBookmarks: RecyclerView

    private var isNavMode = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var skipChecker: Runnable? = null

    private val videoExtensions = listOf(
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv",
        ".webm", ".m3u8", ".m3u", ".ts", ".mpd"
    )
    private val adDomains = mutableSetOf<String>()

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

        webView.loadUrl("https://ya.ru")
        startSkipChecker()
    }

    // ─── Ad domains ──────────────────────────────────────────────────────────

    private fun loadAdDomains() {
        try {
            assets.open("adblock.txt").bufferedReader().forEachLine { line ->
                val t = line.trim()
                if (t.isNotEmpty() && !t.startsWith("#")) adDomains.add(t)
            }
        } catch (_: Exception) {}
    }

    // ─── WebView setup ───────────────────────────────────────────────────────

    private fun setupWebView() {
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Linux; Android 14; TV) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host ?: return null
                if (adDomains.any { host == it || host.endsWith(".$it") })
                    return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (videoExtensions.any { url.contains(it, ignoreCase = true) }) {
                    openInExoPlayer(url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                etUrl.setText(url)
                updateBookmarkButton(url)
                exitNavMode()
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectJs()
                updateNavButtons()
                webView.requestFocus()
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
            }

            override fun onHideCustomView() {
                customView?.let { (window.decorView as ViewGroup).removeView(it) }
                customView = null; cb?.onCustomViewHidden()
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                webView.visibility = View.VISIBLE
                findViewById<LinearLayout>(R.id.nav_bar).visibility = View.VISIBLE
                webView.requestFocus()
            }
        }
    }

    // ─── JS injection ────────────────────────────────────────────────────────

    private fun injectJs() {
        val js = """
        (function(){
            if(window._xbReady) return;
            window._xbReady = true;

            window._xb = {
                els: [], idx: -1,

                visible: function(el){
                    var s = getComputedStyle(el);
                    if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0') return false;
                    var r = el.getBoundingClientRect();
                    return r.width>0 && r.height>0;
                },

                clickable: function(){
                    return Array.from(document.querySelectorAll(
                        'a[href],button:not([disabled]),[role="button"],input[type="submit"],input[type="button"]'
                    )).filter(el => this.visible(el));
                },

                hl: function(el){
                    document.querySelectorAll('[data-xb]').forEach(e=>{
                        e.style.outline=e._xbOld||''; delete e._xbOld; e.removeAttribute('data-xb');
                    });
                    el._xbOld = el.style.outline||'';
                    el.setAttribute('data-xb','1');
                    el.style.outline='3px solid #00B4FF';
                    el.scrollIntoView({block:'nearest',behavior:'smooth'});
                },

                enter: function(){
                    this.els = this.clickable();
                    this.idx = -1;
                    return this.els.length;
                },

                next: function(){
                    if(!this.els.length) this.els=this.clickable();
                    if(!this.els.length) return 0;
                    this.idx=(this.idx+1)%this.els.length;
                    this.hl(this.els[this.idx]);
                    return this.els.length;
                },

                prev: function(){
                    if(!this.els.length) this.els=this.clickable();
                    if(!this.els.length) return 0;
                    this.idx=this.idx<=0?this.els.length-1:this.idx-1;
                    this.hl(this.els[this.idx]);
                    return this.els.length;
                },

                click: function(){
                    if(this.idx>=0&&this.idx<this.els.length){
                        document.querySelectorAll('[data-xb]').forEach(e=>{
                            e.style.outline=e._xbOld||''; e.removeAttribute('data-xb');
                        });
                        this.els[this.idx].click();
                        this.els=[]; this.idx=-1;
                    }
                },

                exit: function(){
                    document.querySelectorAll('[data-xb]').forEach(e=>{
                        e.style.outline=e._xbOld||''; e.removeAttribute('data-xb');
                    });
                    this.els=[]; this.idx=-1;
                },

                /* Skip detection — ONLY when a video element is actually playing */
                checkSkip: function(){
                    var videos = Array.from(document.querySelectorAll('video'));
                    var playing = videos.some(function(v){
                        return !v.paused && !v.ended && v.readyState>2 && v.currentTime>0;
                    });
                    if(!playing) return 0;

                    var skipTexts = ['пропустить','skip','закрыть','close'];
                    var sel = [
                        '.ytp-skip-ad-button','[class*="skip-ad"]','[class*="ad-skip"]',
                        '[class*="skipAd"]','[id*="skip-ad"]','[class*="skip_ad"]'
                    ].join(',');
                    var byClass = [];
                    try{ byClass=Array.from(document.querySelectorAll(sel)); }catch(e){}
                    var byText = Array.from(document.querySelectorAll(
                        'button,[role="button"]'
                    )).filter(el=>{
                        var t=el.textContent.toLowerCase().trim();
                        return skipTexts.some(s=>t===s||t.startsWith(s));
                    });
                    var all=[...byClass,...byText].filter((el,i,a)=>
                        a.indexOf(el)===i && this.visible(el)
                    );
                    return all.length;
                },

                clickSkip: function(){
                    var videos = Array.from(document.querySelectorAll('video'));
                    var playing = videos.some(function(v){
                        return !v.paused && !v.ended && v.readyState>2 && v.currentTime>0;
                    });
                    if(!playing) return false;
                    var sel=['.ytp-skip-ad-button','[class*="skip-ad"]','[class*="ad-skip"]'].join(',');
                    var btns=[];
                    try{ btns=Array.from(document.querySelectorAll(sel)).filter(el=>this.visible(el)); }catch(e){}
                    if(!btns.length){
                        var skipTexts=['пропустить','skip'];
                        btns=Array.from(document.querySelectorAll('button,[role="button"]'))
                            .filter(el=>{
                                var t=el.textContent.toLowerCase().trim();
                                return skipTexts.some(s=>t===s||t.startsWith(s))&&this.visible(el);
                            });
                    }
                    if(btns.length){ btns[0].click(); return true; }
                    return false;
                }
            };
        })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ─── Skip checker (polls every second, only fires when video plays) ──────

    private fun startSkipChecker() {
        skipChecker = object : Runnable {
            override fun run() {
                webView.evaluateJavascript("window._xb ? window._xb.checkSkip() : 0") { r ->
                    val n = r?.trim()?.toIntOrNull() ?: 0
                    if (n > 0) showStatus("Реклама — ОК для пропуска")
                    else if (tvStatus.text == "Реклама — ОК для пропуска")
                        tvStatus.visibility = View.GONE
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(skipChecker!!, 1000)
    }

    // ─── URL bar ─────────────────────────────────────────────────────────────

    private fun setupUrlBar() {
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE) {
                navigate(etUrl.text.toString().trim())
                webView.requestFocus()
                true
            } else false
        }
        // DOWN from URL bar → WebView
        etUrl.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                webView.requestFocus()
                true
            } else false
        }
    }

    private fun navigate(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://yandex.ru/search/?text=${android.net.Uri.encode(input)}"
        }
        webView.loadUrl(url)
    }

    // ─── Buttons ─────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        btnBookmarkAdd.setOnClickListener {
            val url = webView.url ?: return@setOnClickListener
            val title = webView.title ?: url
            if (BookmarkManager.contains(this, url)) {
                BookmarkManager.remove(this, url)
                Toast.makeText(this, getString(R.string.bookmark_removed), Toast.LENGTH_SHORT).show()
            } else {
                BookmarkManager.add(this, title, url)
                Toast.makeText(this, getString(R.string.bookmark_added), Toast.LENGTH_SHORT).show()
            }
            updateBookmarkButton(url)
            refreshBookmarksList()
        }
        btnBookmarks.setOnClickListener {
            panelBookmarks.visibility =
                if (panelBookmarks.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (panelBookmarks.visibility == View.VISIBLE) refreshBookmarksList()
        }
    }

    private fun updateBookmarkButton(url: String) {
        btnBookmarkAdd.setImageResource(
            if (BookmarkManager.contains(this, url)) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )
    }

    private fun updateNavButtons() {
        btnBack.alpha    = if (webView.canGoBack()) 1f else 0.4f
        btnForward.alpha = if (webView.canGoForward()) 1f else 0.4f
    }

    // ─── Bookmarks ───────────────────────────────────────────────────────────

    private fun setupBookmarksList() {
        rvBookmarks.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        refreshBookmarksList()
    }

    private fun refreshBookmarksList() {
        val items = BookmarkManager.getAll(this)
        rvBookmarks.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(
                    layoutInflater.inflate(R.layout.item_bookmark, parent, false)
                ) {}
            override fun getItemCount() = items.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val bm = items[position]
                holder.itemView.findViewById<TextView>(R.id.tv_bookmark_title).text = bm.title
                holder.itemView.setOnClickListener {
                    webView.loadUrl(bm.url)
                    panelBookmarks.visibility = View.GONE
                    webView.requestFocus()
                }
                holder.itemView.findViewById<ImageButton>(R.id.btn_delete_bookmark)
                    .setOnClickListener {
                        BookmarkManager.remove(this@MainActivity, bm.url)
                        refreshBookmarksList()
                    }
            }
        }
    }

    // ─── Nav mode ────────────────────────────────────────────────────────────

    private fun enterNavMode() {
        webView.evaluateJavascript("window._xb ? window._xb.enter() : 0") { r ->
            val n = r?.trim()?.toIntOrNull() ?: 0
            if (n > 0) {
                isNavMode = true
                showStatus(getString(R.string.nav_mode_on))
                webView.evaluateJavascript("window._xb.next()", null)
            }
        }
    }

    private fun exitNavMode() {
        isNavMode = false
        webView.evaluateJavascript("if(window._xb) window._xb.exit()", null)
        tvStatus.visibility = View.GONE
    }

    private fun showStatus(text: String) {
        tvStatus.text = text
        tvStatus.visibility = View.VISIBLE
    }

    // ─── D-pad — все события через dispatchKeyEvent ───────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        // URL bar — DOWN/BACK возвращает в WebView, остальное обрабатывает сам
        if (etUrl.isFocused) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK -> {
                    webView.requestFocus(); true
                }
                else -> super.dispatchKeyEvent(event)
            }
        }

        // Закладки
        if (panelBookmarks.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                panelBookmarks.visibility = View.GONE; return true
            }
            return super.dispatchKeyEvent(event)
        }

        return when (event.keyCode) {

            KeyEvent.KEYCODE_DPAD_UP -> {
                when {
                    isNavMode -> webView.evaluateJavascript("window._xb.prev()", null)
                    webView.scrollY <= 0 -> {
                        // В самом верху — переходим в URL-строку
                        etUrl.requestFocus()
                        etUrl.selectAll()
                    }
                    else -> webView.scrollBy(0, -250)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isNavMode) webView.evaluateJavascript("window._xb.next()", null)
                else webView.scrollBy(0, 250)
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!isNavMode) webView.scrollBy(-250, 0)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isNavMode) webView.scrollBy(250, 0)
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (isNavMode) {
                    webView.evaluateJavascript("window._xb.click()", null)
                    exitNavMode()
                } else {
                    // Проверяем кнопку пропуска рекламы (только если видео играет)
                    webView.evaluateJavascript("window._xb ? window._xb.checkSkip() : 0") { r ->
                        if ((r?.trim()?.toIntOrNull() ?: 0) > 0) {
                            webView.evaluateJavascript("window._xb.clickSkip()", null)
                            tvStatus.visibility = View.GONE
                        } else {
                            enterNavMode()
                        }
                    }
                }
                true
            }

            KeyEvent.KEYCODE_BACK -> {
                when {
                    isNavMode -> { exitNavMode(); true }
                    webView.canGoBack() -> { webView.goBack(); true }
                    else -> { finish(); true }
                }
            }

            else -> super.dispatchKeyEvent(event)
        }
    }

    // ─── Video / misc ────────────────────────────────────────────────────────

    private fun openInExoPlayer(url: String) {
        startActivity(Intent(this, VideoActivity::class.java).apply {
            putExtra(VideoActivity.EXTRA_URL, url)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        skipChecker?.let { handler.removeCallbacks(it) }
        webView.destroy()
    }
}
