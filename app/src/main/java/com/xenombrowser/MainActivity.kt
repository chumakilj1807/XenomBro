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
    private var skipCheckJob: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Video file extensions to intercept and open in ExoPlayer
    private val videoExtensions = listOf(
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv",
        ".webm", ".m3u8", ".m3u", ".ts", ".mpd"
    )

    // Ad domains loaded from assets
    private val adDomains = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadAdDomains()

        webView = findViewById(R.id.web_view)
        etUrl = findViewById(R.id.et_url)
        tvStatus = findViewById(R.id.tv_status)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnBookmarkAdd = findViewById(R.id.btn_bookmark_add)
        btnBookmarks = findViewById(R.id.btn_bookmarks)
        panelBookmarks = findViewById(R.id.panel_bookmarks)
        rvBookmarks = findViewById(R.id.rv_bookmarks)

        setupWebView()
        setupUrlBar()
        setupButtons()
        setupBookmarksList()

        webView.loadUrl("https://ya.ru")
        startSkipButtonChecker()
    }

    private fun loadAdDomains() {
        try {
            assets.open("adblock.txt").bufferedReader().forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    adDomains.add(trimmed)
                }
            }
        } catch (_: Exception) {}
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host ?: return null
                // Block ad domains
                if (adDomains.any { host == it || host.endsWith(".$it") }) {
                    return WebResourceResponse("text/plain", "utf-8",
                        "".byteInputStream())
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // Intercept direct video file links → ExoPlayer
                if (videoExtensions.any { url.contains(it, ignoreCase = true) }) {
                    openInExoPlayer(url, view.title ?: "")
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
                injectNavigationJs()
                updateNavButtons()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            private var customViewCallback: CustomViewCallback? = null

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                // Video went fullscreen inside WebView
                customView = view
                customViewCallback = callback
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
                (window.decorView as ViewGroup).addView(view)
                webView.visibility = View.GONE
                findViewById<LinearLayout>(R.id.nav_bar).visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let {
                    (window.decorView as ViewGroup).removeView(it)
                }
                customView = null
                customViewCallback?.onCustomViewHidden()
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                webView.visibility = View.VISIBLE
                findViewById<LinearLayout>(R.id.nav_bar).visibility = View.VISIBLE
            }
        }
    }

    private fun injectNavigationJs() {
        val js = """
            (function() {
                if (window._xenomBrowserInited) return;
                window._xenomBrowserInited = true;

                window._xb = {
                    elements: [],
                    index: -1,

                    getClickable: function() {
                        return Array.from(document.querySelectorAll(
                            'a[href], button:not([disabled]), [role="button"], ' +
                            'input[type="button"], input[type="submit"], [onclick]'
                        )).filter(function(el) {
                            var s = window.getComputedStyle(el);
                            if (s.display === 'none' || s.visibility === 'hidden') return false;
                            var r = el.getBoundingClientRect();
                            return r.width > 0 && r.height > 0;
                        });
                    },

                    getSkipButtons: function() {
                        var skipTexts = ['пропустить', 'skip', 'закрыть', 'close'];
                        var selectors = [
                            '.ytp-skip-ad-button', '[class*="skip-ad"]', '[class*="ad-skip"]',
                            '[class*="skip"]', '[id*="skip"]', '[class*="adClose"]',
                            '[class*="close-ad"]', '[class*="closeAd"]'
                        ].join(',');

                        var bySelector = [];
                        try { bySelector = Array.from(document.querySelectorAll(selectors)); } catch(e) {}

                        var byText = Array.from(document.querySelectorAll('button, [role="button"], a'))
                            .filter(function(el) {
                                var text = el.textContent.toLowerCase().trim();
                                return skipTexts.some(function(t) { return text.includes(t); });
                            });

                        var all = bySelector.concat(byText);
                        return all.filter(function(el, i, arr) {
                            if (arr.indexOf(el) !== i) return false;
                            var s = window.getComputedStyle(el);
                            if (s.display === 'none' || s.visibility === 'hidden') return false;
                            var r = el.getBoundingClientRect();
                            return r.width > 0 && r.height > 0;
                        });
                    },

                    clearHighlight: function() {
                        document.querySelectorAll('[data-xb-focused]').forEach(function(el) {
                            el.style.outline = el.getAttribute('data-xb-outline') || '';
                            el.removeAttribute('data-xb-focused');
                            el.removeAttribute('data-xb-outline');
                        });
                    },

                    highlight: function(el) {
                        this.clearHighlight();
                        el.setAttribute('data-xb-outline', el.style.outline || '');
                        el.setAttribute('data-xb-focused', '1');
                        el.style.outline = '3px solid #00B4FF';
                        el.scrollIntoView({block: 'nearest', behavior: 'smooth'});
                    },

                    enterNav: function() {
                        this.elements = this.getClickable();
                        this.index = -1;
                        return this.elements.length;
                    },

                    next: function() {
                        if (this.elements.length === 0) this.elements = this.getClickable();
                        if (this.elements.length === 0) return 0;
                        this.index = (this.index + 1) % this.elements.length;
                        this.highlight(this.elements[this.index]);
                        return this.elements.length;
                    },

                    prev: function() {
                        if (this.elements.length === 0) this.elements = this.getClickable();
                        if (this.elements.length === 0) return 0;
                        this.index = this.index <= 0 ? this.elements.length - 1 : this.index - 1;
                        this.highlight(this.elements[this.index]);
                        return this.elements.length;
                    },

                    click: function() {
                        if (this.index >= 0 && this.index < this.elements.length) {
                            this.clearHighlight();
                            this.elements[this.index].click();
                            this.elements = [];
                            this.index = -1;
                        }
                    },

                    exit: function() {
                        this.clearHighlight();
                        this.elements = [];
                        this.index = -1;
                    },

                    checkSkip: function() {
                        var btns = this.getSkipButtons();
                        return btns.length;
                    },

                    clickSkip: function() {
                        var btns = this.getSkipButtons();
                        if (btns.length > 0) { btns[0].click(); return true; }
                        return false;
                    }
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // Periodically check for skip buttons
    private fun startSkipButtonChecker() {
        skipCheckJob = object : Runnable {
            override fun run() {
                if (!isNavMode) {
                    webView.evaluateJavascript("window._xb ? window._xb.checkSkip() : 0") { result ->
                        val count = result?.trim()?.toIntOrNull() ?: 0
                        if (count > 0) {
                            showStatus(getString(R.string.skip_found))
                        } else if (tvStatus.visibility == View.VISIBLE &&
                            tvStatus.text == getString(R.string.skip_found)) {
                            tvStatus.visibility = View.GONE
                        }
                    }
                }
                handler.postDelayed(this, 800)
            }
        }
        handler.postDelayed(skipCheckJob!!, 800)
    }

    private fun setupUrlBar() {
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigate(etUrl.text.toString().trim())
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

    private fun setupButtons() {
        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
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
        val isSaved = BookmarkManager.contains(this, url)
        btnBookmarkAdd.setImageResource(
            if (isSaved) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )
    }

    private fun setupBookmarksList() {
        rvBookmarks.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        refreshBookmarksList()
    }

    private fun refreshBookmarksList() {
        val items = BookmarkManager.getAll(this)
        rvBookmarks.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = layoutInflater.inflate(R.layout.item_bookmark, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun getItemCount() = items.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val bm = items[position]
                holder.itemView.findViewById<TextView>(R.id.tv_bookmark_title).text = bm.title
                holder.itemView.setOnClickListener {
                    webView.loadUrl(bm.url)
                    panelBookmarks.visibility = View.GONE
                    webView.requestFocus()
                }
                holder.itemView.setOnLongClickListener {
                    BookmarkManager.remove(this@MainActivity, bm.url)
                    refreshBookmarksList()
                    true
                }
                holder.itemView.findViewById<ImageButton>(R.id.btn_delete_bookmark).setOnClickListener {
                    BookmarkManager.remove(this@MainActivity, bm.url)
                    refreshBookmarksList()
                }
            }
        }
    }

    private fun updateNavButtons() {
        btnBack.alpha = if (webView.canGoBack()) 1f else 0.4f
        btnForward.alpha = if (webView.canGoForward()) 1f else 0.4f
    }

    private fun enterNavMode() {
        isNavMode = true
        webView.evaluateJavascript("window._xb ? window._xb.enterNav() : 0") { count ->
            val n = count?.trim()?.toIntOrNull() ?: 0
            if (n > 0) {
                showStatus(getString(R.string.nav_mode_on))
                webView.evaluateJavascript("window._xb.next()", null)
            } else {
                isNavMode = false
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

    private fun openInExoPlayer(url: String, title: String) {
        startActivity(Intent(this, VideoActivity::class.java).apply {
            putExtra(VideoActivity.EXTRA_URL, url)
            putExtra(VideoActivity.EXTRA_TITLE, title)
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If URL bar is focused — let it handle keys
        if (etUrl.isFocused) return super.onKeyDown(keyCode, event)

        // If bookmarks panel is open
        if (panelBookmarks.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                panelBookmarks.visibility = View.GONE
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isNavMode) {
                    webView.evaluateJavascript("window._xb.prev()", null)
                    true
                } else {
                    webView.scrollBy(0, -200)
                    true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isNavMode) {
                    webView.evaluateJavascript("window._xb.next()", null)
                    true
                } else {
                    webView.scrollBy(0, 200)
                    true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!isNavMode) { webView.scrollBy(-200, 0); true }
                else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isNavMode) { webView.scrollBy(200, 0); true }
                else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (isNavMode) {
                    webView.evaluateJavascript("window._xb.click()", null)
                    exitNavMode()
                } else {
                    // Check for skip buttons first
                    webView.evaluateJavascript("window._xb ? window._xb.checkSkip() : 0") { result ->
                        val count = result?.trim()?.toIntOrNull() ?: 0
                        if (count > 0) {
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
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        skipCheckJob?.let { handler.removeCallbacks(it) }
        webView.destroy()
    }
}
