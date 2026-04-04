package com.xenombrowser

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    // ─── States ───────────────────────────────────────────────────────────────
    // UI_NAV   : стрелки = фокус между кнопками/URL/WebPanel
    // WEB      : пользователь внутри браузера, стрелки = скролл
    // LINK_NAV : режим навигации по ссылкам (JS подсветка)
    private enum class State { UI_NAV, WEB, LINK_NAV }
    private var state = State.UI_NAV

    // ─── Views ────────────────────────────────────────────────────────────────
    private lateinit var webView: WebView
    private lateinit var webviewPanel: FrameLayout
    private lateinit var hintOverlay: LinearLayout
    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnBookmarkAdd: ImageButton
    private lateinit var btnBookmarks: ImageButton
    private lateinit var panelBookmarks: LinearLayout
    private lateinit var rvBookmarks: RecyclerView

    // ─── Long-press OK detection ──────────────────────────────────────────────
    private var okDownAt = 0L
    private val LONG_PRESS_MS = 600L

    // ─── Misc ─────────────────────────────────────────────────────────────────
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var skipChecker: Runnable? = null
    private val adDomains = mutableSetOf<String>()
    private val videoExtensions = listOf(
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv",
        ".webm", ".m3u8", ".m3u", ".ts", ".mpd"
    )

    // ─── onCreate ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadAdDomains()

        webView        = findViewById(R.id.web_view)
        webviewPanel   = findViewById(R.id.webview_panel)
        hintOverlay    = findViewById(R.id.webview_hint_overlay)
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
        setupPanelFocus()

        webView.loadUrl("https://ya.ru")
        startSkipChecker()

        // Start in UI_NAV — focus on URL bar
        etUrl.post { etUrl.requestFocus() }
    }

    // ─── WebView panel focus (UI_NAV mode indicator) ─────────────────────────

    private fun setupPanelFocus() {
        webviewPanel.setOnFocusChangeListener { _, hasFocus ->
            if (state == State.UI_NAV) {
                // Show hint overlay when panel is focused in UI_NAV
                hintOverlay.visibility = if (hasFocus) View.VISIBLE else View.GONE
                webviewPanel.setBackgroundResource(
                    if (hasFocus) android.R.color.transparent else android.R.color.transparent
                )
                if (hasFocus) {
                    webviewPanel.foreground = getDrawable(R.drawable.bg_panel_focused)
                } else {
                    webviewPanel.foreground = null
                }
            }
        }
    }

    // ─── State transitions ────────────────────────────────────────────────────

    private fun enterWeb() {
        state = State.WEB
        hintOverlay.visibility = View.GONE
        webviewPanel.foreground = getDrawable(R.drawable.bg_panel_active)
        showStatus("Браузер активен  |  ОК — клик   Удержать ОК — выход")
        handler.postDelayed({ if (state == State.WEB) tvStatus.visibility = View.GONE }, 3000)
    }

    private fun exitWeb() {
        state = State.UI_NAV
        webviewPanel.foreground = null
        hintOverlay.visibility = View.GONE
        tvStatus.visibility = View.GONE
        exitNavMode()
        webviewPanel.requestFocus()
    }

    private fun enterLinkNav() {
        webView.evaluateJavascript("window._xb ? window._xb.enter() : 0") { r ->
            val n = r?.trim()?.toIntOrNull() ?: 0
            if (n > 0) {
                state = State.LINK_NAV
                showStatus("Навигация: ↑↓ — выбор   ОК — открыть   НАЗАД — выход")
                webView.evaluateJavascript("window._xb.next()", null)
            }
        }
    }

    private fun exitNavMode() {
        if (state == State.LINK_NAV) state = State.WEB
        webView.evaluateJavascript("if(window._xb) window._xb.exit()", null)
        tvStatus.visibility = View.GONE
    }

    // ─── Key dispatch (ALL keys go through here) ─────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER

        // Track OK press time for long-press detection
        if (isOk) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) okDownAt = System.currentTimeMillis()
                    return true  // consume — handle on UP
                }
                KeyEvent.ACTION_UP -> {
                    val held = System.currentTimeMillis() - okDownAt
                    okDownAt = 0L
                    return handleOk(held >= LONG_PRESS_MS)
                }
            }
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        // URL bar is focused — let it handle typing, only intercept DOWN/BACK
        if (etUrl.isFocused) {
            return when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { webviewPanel.requestFocus(); true }
                KeyEvent.KEYCODE_BACK -> { webviewPanel.requestFocus(); true }
                else -> super.dispatchKeyEvent(event)
            }
        }

        // Bookmarks panel open
        if (panelBookmarks.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) {
                panelBookmarks.visibility = View.GONE; return true
            }
            return super.dispatchKeyEvent(event)
        }

        return when (state) {
            State.UI_NAV -> handleKeyUiNav(code)
            State.WEB    -> handleKeyWeb(code)
            State.LINK_NAV -> handleKeyLinkNav(code)
        }
    }

    private fun handleOk(isLong: Boolean): Boolean {
        if (etUrl.isFocused) return false  // let EditText handle

        return when (state) {
            State.UI_NAV -> {
                if (webviewPanel.isFocused) {
                    if (isLong) { enterWeb(); true }
                    else {
                        // Short OK on panel — show hint
                        showStatus("Удержите ОК для входа в браузер")
                        handler.postDelayed({ tvStatus.visibility = View.GONE }, 2000)
                        true
                    }
                } else {
                    // Other UI element — let system handle
                    super.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                }
            }
            State.WEB -> {
                if (isLong) { exitWeb(); true }
                else {
                    // Short OK — check skip first, then enter link nav
                    webView.evaluateJavascript("window._xb ? window._xb.checkSkip() : 0") { r ->
                        if ((r?.trim()?.toIntOrNull() ?: 0) > 0) {
                            webView.evaluateJavascript("window._xb.clickSkip()", null)
                            tvStatus.visibility = View.GONE
                        } else {
                            enterLinkNav()
                        }
                    }
                    true
                }
            }
            State.LINK_NAV -> {
                // OK in link nav = click
                webView.evaluateJavascript("window._xb.click()", null)
                exitNavMode()
                true
            }
        }
    }

    private fun handleKeyUiNav(code: Int): Boolean {
        // In UI_NAV — let Android's focus system handle arrows naturally
        return when (code) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, code)
            )
        }
    }

    private fun handleKeyWeb(code: Int): Boolean {
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP    -> { webView.scrollBy(0, -250); true }
            KeyEvent.KEYCODE_DPAD_DOWN  -> { webView.scrollBy(0, 250);  true }
            KeyEvent.KEYCODE_DPAD_LEFT  -> { webView.scrollBy(-250, 0); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { webView.scrollBy(250, 0);  true }
            KeyEvent.KEYCODE_BACK -> {
                when {
                    webView.canGoBack() -> { webView.goBack(); true }
                    else -> { exitWeb(); true }
                }
            }
            else -> super.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        }
    }

    private fun handleKeyLinkNav(code: Int): Boolean {
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP   -> {
                webView.evaluateJavascript("window._xb.prev()", null); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                webView.evaluateJavascript("window._xb.next()", null); true
            }
            KeyEvent.KEYCODE_BACK -> { exitNavMode(); true }
            else -> super.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        }
    }

    // ─── Skip checker ─────────────────────────────────────────────────────────

    private fun startSkipChecker() {
        skipChecker = object : Runnable {
            override fun run() {
                if (state == State.WEB || state == State.LINK_NAV) {
                    webView.evaluateJavascript("window._xb ? window._xb.checkSkip() : 0") { r ->
                        val n = r?.trim()?.toIntOrNull() ?: 0
                        if (n > 0) showStatus("Реклама — нажмите ОК для пропуска")
                        else if (tvStatus.text == "Реклама — нажмите ОК для пропуска")
                            tvStatus.visibility = View.GONE
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(skipChecker!!, 1000)
    }

    // ─── WebView setup ────────────────────────────────────────────────────────

    private fun setupWebView() {
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
                if (videoExtensions.any { url.contains(it, ignoreCase = true) }) {
                    openInExoPlayer(url); return true
                }
                return false
            }
            override fun onPageStarted(view: WebView, url: String, fav: Bitmap?) {
                etUrl.setText(url)
                updateBookmarkButton(url)
                if (state == State.LINK_NAV) exitNavMode()
            }
            override fun onPageFinished(view: WebView, url: String) {
                injectJs()
                updateNavButtons()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            private var cb: CustomViewCallback? = null
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view; cb = callback
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
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
            }
        }
    }

    // ─── JS injection ─────────────────────────────────────────────────────────

    private fun injectJs() {
        val js = """
        (function(){
            if(window._xbReady) return;
            window._xbReady = true;
            window._xb = {
                els:[], idx:-1,
                vis:function(el){
                    var s=getComputedStyle(el);
                    if(s.display==='none'||s.visibility==='hidden') return false;
                    var r=el.getBoundingClientRect();
                    return r.width>0&&r.height>0;
                },
                all:function(){
                    return Array.from(document.querySelectorAll(
                        'a[href],button:not([disabled]),[role="button"],input[type="submit"],input[type="button"]'
                    )).filter(el=>this.vis(el));
                },
                hl:function(el){
                    document.querySelectorAll('[data-xb]').forEach(e=>{
                        e.style.outline=e._xbOld||'';e.removeAttribute('data-xb');
                    });
                    el._xbOld=el.style.outline||'';
                    el.setAttribute('data-xb','1');
                    el.style.outline='3px solid #00B4FF';
                    el.scrollIntoView({block:'nearest',behavior:'smooth'});
                },
                enter:function(){this.els=this.all();this.idx=-1;return this.els.length;},
                next:function(){
                    if(!this.els.length)this.els=this.all();
                    if(!this.els.length)return 0;
                    this.idx=(this.idx+1)%this.els.length;
                    this.hl(this.els[this.idx]);return this.els.length;
                },
                prev:function(){
                    if(!this.els.length)this.els=this.all();
                    if(!this.els.length)return 0;
                    this.idx=this.idx<=0?this.els.length-1:this.idx-1;
                    this.hl(this.els[this.idx]);return this.els.length;
                },
                click:function(){
                    if(this.idx>=0&&this.idx<this.els.length){
                        document.querySelectorAll('[data-xb]').forEach(e=>{
                            e.style.outline=e._xbOld||'';e.removeAttribute('data-xb');
                        });
                        this.els[this.idx].click();this.els=[];this.idx=-1;
                    }
                },
                exit:function(){
                    document.querySelectorAll('[data-xb]').forEach(e=>{
                        e.style.outline=e._xbOld||'';e.removeAttribute('data-xb');
                    });
                    this.els=[];this.idx=-1;
                },
                checkSkip:function(){
                    var vids=Array.from(document.querySelectorAll('video'));
                    var playing=vids.some(v=>!v.paused&&!v.ended&&v.readyState>2&&v.currentTime>0);
                    if(!playing)return 0;
                    var sel='.ytp-skip-ad-button,[class*="skip-ad"],[class*="ad-skip"],[class*="skipAd"]';
                    var byClass=[];try{byClass=Array.from(document.querySelectorAll(sel));}catch(e){}
                    var byText=Array.from(document.querySelectorAll('button,[role="button"]'))
                        .filter(el=>{var t=el.textContent.toLowerCase().trim();
                            return(t==='пропустить'||t==='skip ad'||t.startsWith('skip ad'))&&this.vis(el);});
                    return[...byClass,...byText].filter((el,i,a)=>a.indexOf(el)===i&&this.vis(el)).length;
                },
                clickSkip:function(){
                    var sel='.ytp-skip-ad-button,[class*="skip-ad"],[class*="ad-skip"]';
                    var btns=[];try{btns=Array.from(document.querySelectorAll(sel)).filter(el=>this.vis(el));}catch(e){}
                    if(!btns.length){
                        btns=Array.from(document.querySelectorAll('button,[role="button"]'))
                            .filter(el=>{var t=el.textContent.toLowerCase().trim();
                                return(t==='пропустить'||t==='skip ad')&&this.vis(el);});
                    }
                    if(btns.length){btns[0].click();return true;}return false;
                }
            };
        })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun loadAdDomains() {
        try {
            assets.open("adblock.txt").bufferedReader().forEachLine { line ->
                val t = line.trim()
                if (t.isNotEmpty() && !t.startsWith("#")) adDomains.add(t)
            }
        } catch (_: Exception) {}
    }

    private fun showStatus(text: String) {
        tvStatus.text = text
        tvStatus.visibility = View.VISIBLE
    }

    private fun openInExoPlayer(url: String) {
        startActivity(Intent(this, VideoActivity::class.java).apply {
            putExtra(VideoActivity.EXTRA_URL, url)
        })
    }

    private fun updateNavButtons() {
        btnBack.alpha    = if (webView.canGoBack()) 1f else 0.4f
        btnForward.alpha = if (webView.canGoForward()) 1f else 0.4f
    }

    private fun updateBookmarkButton(url: String) {
        btnBookmarkAdd.setImageResource(
            if (BookmarkManager.contains(this, url)) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )
    }

    private fun setupUrlBar() {
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                val input = etUrl.text.toString().trim()
                val url = when {
                    input.startsWith("http://") || input.startsWith("https://") -> input
                    input.contains(".") && !input.contains(" ") -> "https://$input"
                    else -> "https://yandex.ru/search/?text=${android.net.Uri.encode(input)}"
                }
                webView.loadUrl(url)
                webviewPanel.requestFocus()
                true
            } else false
        }
    }

    private fun setupButtons() {
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        btnBookmarkAdd.setOnClickListener {
            val url = webView.url ?: return@setOnClickListener
            if (BookmarkManager.contains(this, url)) {
                BookmarkManager.remove(this, url)
                Toast.makeText(this, getString(R.string.bookmark_removed), Toast.LENGTH_SHORT).show()
            } else {
                BookmarkManager.add(this, webView.title ?: url, url)
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

    private fun setupBookmarksList() {
        rvBookmarks.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        refreshBookmarksList()
    }

    private fun refreshBookmarksList() {
        val items = BookmarkManager.getAll(this)
        rvBookmarks.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(
                    layoutInflater.inflate(R.layout.item_bookmark, parent, false)) {}
            override fun getItemCount() = items.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val bm = items[pos]
                h.itemView.findViewById<TextView>(R.id.tv_bookmark_title).text = bm.title
                h.itemView.setOnClickListener {
                    webView.loadUrl(bm.url)
                    panelBookmarks.visibility = View.GONE
                    webviewPanel.requestFocus()
                }
                h.itemView.findViewById<ImageButton>(R.id.btn_delete_bookmark).setOnClickListener {
                    BookmarkManager.remove(this@MainActivity, bm.url)
                    refreshBookmarksList()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        skipChecker?.let { handler.removeCallbacks(it) }
        webView.destroy()
    }
}
