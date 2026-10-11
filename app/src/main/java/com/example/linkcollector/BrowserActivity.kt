package com.example.linkcollector

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.util.Log

class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlEditText: EditText
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var goButton: ImageButton
    private lateinit var pasteUrlButton: ImageButton
    private lateinit var addFavoriteButton: ImageButton
    private lateinit var favoritesContainer: LinearLayout

    private lateinit var favoriteDao: FavoriteDao

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webView)
        urlEditText = findViewById(R.id.urlEditText)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        goButton = findViewById(R.id.goButton)
        pasteUrlButton = findViewById(R.id.pasteUrlButton)
        addFavoriteButton = findViewById(R.id.addFavoriteButton)
        favoritesContainer = findViewById(R.id.favoritesContainer)

        favoriteDao = AppDatabase.getDatabase(this).favoriteDao()

        webView.settings.javaScriptEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                Log.d("LinkPick", "override: $url") // ▼確認用ログ

                val supportedHosts = listOf(
                    "1fichier.com", "rapidgator.net", "turbobit.net", "hitfile.net",
                    "mega.nz", "4shared.com", "mediafire.com", "drive.google.com",
                    "scribd.com", "pixeldrain.com", "filefactory.com", "ddownload.com",
                    "fastbit.cc", "filedot.to", "filespace.com", "hexupload.net",
                    "isra.cloud", "katfile.com", "mexashare.com", "mp4upload.com",
                    "nitroflare.com", "prefiles.com", "sharemods.com", "uploadgig.com",
                    "uptobox.com", "wdupload.com", "wayupload.com", "k2s.cc",
                    "takefile.net", "tezfiles.com", "dailymotion.com", "vimeo.com",
                    "soundcloud.com", "twitch.tv", "archive.org", "dood.com",
                    "streamtape.com", "voe.sx", "real-debrid.com",
                    "dailyuploads.net", "frdl.to", "frdl.io", "freedl.ink", "uploady.io",
                    "katfile.biz", "katfile.cloud", "katfile.online"
                )

                if (supportedHosts.any { url.contains(it) }) {
                    val resultIntent = Intent()
                    resultIntent.putExtra("newLink", url)
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                    return true
                }
                return false
            }

            // ▼確認用ログ
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d("LinkPick", "started: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.post { view.scrollY = WebViewState.lastScrollY }
            }
        }

        val initialUrl = WebViewState.lastUrl ?: "https://www.google.com"
        webView.loadUrl(initialUrl)
        urlEditText.setText(initialUrl)

        setupActionButtons()
        observeFavorites()
    }

    private fun setupActionButtons() {
        backButton.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        forwardButton.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        goButton.setOnClickListener { finish() }

        pasteUrlButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val pasteData = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!pasteData.isNullOrBlank()) {
                    urlEditText.setText(pasteData)
                    loadUrlFromEditText()
                }
            }
        }

        urlEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrlFromEditText()
                true
            } else { false }
        }

        // ★ お気に入り追加ボタン
        addFavoriteButton.setOnClickListener {
            showAddFavoriteDialog()
        }
    }

    private fun showAddFavoriteDialog() {
        val currentUrl = webView.url ?: return
        val currentTitle = webView.title ?: currentUrl

        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_favorite, null)
        dialog.setContentView(view)

        val nameInput = view.findViewById<TextInputEditText>(R.id.favoriteNameEditText)
        val urlInput = view.findViewById<TextInputEditText>(R.id.favoriteUrlEditText)
        val cancelButton = view.findViewById<Button>(R.id.favoriteCancelButton)
        val addButton = view.findViewById<Button>(R.id.favoriteAddButton)

        nameInput.setText(currentTitle)
        urlInput.setText(currentUrl)

        cancelButton.setOnClickListener { dialog.dismiss() }

        addButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()
            if (name.isNotBlank() && url.isNotBlank()) {
                lifecycleScope.launch {
                    favoriteDao.insert(FavoriteItem(name = name, url = url))
                }
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun observeFavorites() {
        lifecycleScope.launch {
            favoriteDao.getAll().collectLatest { favorites ->
                favoritesContainer.removeAllViews()
                favorites.forEach { item ->
                    addFavoriteChip(item)
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun addFavoriteChip(item: FavoriteItem) {
        val chip = LayoutInflater.from(this)
            .inflate(R.layout.item_favorite_chip, favoritesContainer, false)
        val label = chip.findViewById<TextView>(R.id.chipLabel)
        label.text = item.name

        // タップで移動
        chip.setOnClickListener {
            webView.loadUrl(item.url)
            urlEditText.setText(item.url)
        }

        // 長押しで削除
        chip.setOnLongClickListener {
            lifecycleScope.launch {
                favoriteDao.delete(item)
            }
            true
        }

        favoritesContainer.addView(chip)
    }

    private fun loadUrlFromEditText() {
        var url = urlEditText.text.toString()
        if (url.isNotBlank()) {
            WebViewState.lastScrollY = 0
            webView.clearHistory()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://www.google.com/search?q=$url"
            }
            webView.loadUrl(url)
        }
    }

    private fun saveWebViewState() {
        WebViewState.lastUrl = webView.url
        WebViewState.lastScrollY = webView.scrollY
    }

    override fun onResume() {
        super.onResume()
        webView.postDelayed({ webView.alpha = 1.0f }, 50)
    }

    override fun onPause() {
        super.onPause()
        saveWebViewState()
        webView.alpha = 0.0f
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}

object WebViewState {
    var lastUrl: String? = "https://www.google.com"
    var lastScrollY: Int = 0
}