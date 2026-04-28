package com.example.linkcollector

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlEditText: EditText
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var goButton: ImageButton
    private lateinit var pasteUrlButton: ImageButton // ← この行を追加

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webView)
        urlEditText = findViewById(R.id.urlEditText)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        goButton = findViewById(R.id.goButton)
        pasteUrlButton = findViewById(R.id.pasteUrlButton) // ← この行を追加

        webView.settings.javaScriptEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false

                val supportedHosts = listOf(
                    // ─── メジャーホスト ───
                    "1fichier.com",
                    "rapidgator.net",
                    "turbobit.net",
                    "hitfile.net",
                    "mega.nz",
                    "4shared.com",
                    "mediafire.com",
                    "drive.google.com",
                    "scribd.com",
                    "pixeldrain.com",
                    "filefactory.com",

                    // ─── アップローダー系 ───
                    "ddownload.com",
                    "fastbit.cc",
                    "filedot.to",
                    "filespace.com",
                    "hexupload.net",
                    "isra.cloud",
                    "katfile.com",
                    "mexashare.com",
                    "mp4upload.com",
                    "nitroflare.com",
                    "prefiles.com",
                    "sharemods.com",
                    "uploadgig.com",
                    "uptobox.com",
                    "wdupload.com",
                    "wayupload.com",
                    "k2s.cc",           // keep2share
                    "takefile.net",
                    "tezfiles.com",

                    // ─── 動画ストリーム系 ───
                    "dailymotion.com",
                    "vimeo.com",
                    "soundcloud.com",
                    "twitch.tv",
                    "archive.org",
                    "dood.com",
                    "streamtape.com",
                    "voe.sx",

                    // ─── Real-Debrid 自身 ───
                    "real-debrid.com"
                )

                if (supportedHosts.any { url.contains(it) }) {
                    // --- 新しい判定コードここまで ---

                    // メイン画面に返すためのデータ（Intent）を作成
                    val resultIntent = Intent()
                    resultIntent.putExtra("newLink", url)
                    setResult(Activity.RESULT_OK, resultIntent)
                    // このブラウザ画面を閉じる
                    finish()
                    return true // WebViewにはこのURLを読み込ませない
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // ページ読み込み完了時にスクロール位置を復元
                view?.post { view.scrollY = WebViewState.lastScrollY }
            }
        }

        // 記憶したURLを読み込む
        val initialUrl = WebViewState.lastUrl ?: "https://www.google.com"
        webView.loadUrl(initialUrl)
        urlEditText.setText(initialUrl)

        setupActionButtons()
    }

    private fun setupActionButtons() {
        backButton.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        forwardButton.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        goButton.setOnClickListener { loadUrlFromEditText() }

        // ★新しい貼り付けボタンの処理を追加
        pasteUrlButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val pasteData = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!pasteData.isNullOrBlank()) {
                    urlEditText.setText(pasteData)
                    loadUrlFromEditText() // 貼り付けて即ロード
                }
            }
        }

        urlEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrlFromEditText()
                true
            } else { false }
        }

        // urlEditText.setOnClickListener { urlEditText.selectAll() } // ← この行を削除、またはコメントアウト
    }

    private fun loadUrlFromEditText() {
        var url = urlEditText.text.toString()
        if (url.isNotBlank()) {
            // ★新しいセッションを開始する前にお掃除
            WebViewState.lastScrollY = 0 // スクロール位置の記憶をリセット
            webView.clearHistory()      // Webの閲覧履歴をリセット

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
        // 画面が再度表示されたら、ごく僅かな時間をおいてからWebViewを表示させる
        webView.postDelayed({
            webView.alpha = 1.0f // 透明度を100%（不透明）に戻す
        }, 50)
    }

    override fun onPause() {
        super.onPause()
        // 画面が非表示になる直前に、状態を保存し、WebViewを透明にする
        saveWebViewState()
        webView.alpha = 0.0f // 透明度を0%（完全な透明）にする
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

// WebViewの状態をアプリ内で共有するためのシングルトンオブジェクト
object WebViewState {
    var lastUrl: String? = "https://www.google.com"
    var lastScrollY: Int = 0
}