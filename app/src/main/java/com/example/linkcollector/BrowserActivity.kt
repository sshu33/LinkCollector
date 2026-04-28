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

                // --- ここからが新しい判定コード ---
                val supportedHosts = listOf(
                    // Premium hosts
                    "1fichier.com",
                    "rapidgator.net",
                    "turbobit.net",
                    "hitfile.net",
                    "w.wayupload.com", // wayupload
                    "mega.nz", // mega
                    "4shared.com",
                    "alfafile.net",
                    "apkadmin.com",
                    "cloudvideo.tv",
                    "ddownload.com",
                    "dropapk.com",
                    "dropgalaxy.com", // dropgalaxy.co, .in etc.
                    "fastbit.cc",
                    "file-upload.com",
                    "fileal.com",
                    "filedot.to",
                    "filespace.com",
                    "gigapeta.com",
                    "drive.google.com", // google
                    "hexupload.net",
                    "isra.cloud", // isra
                    "katfile.com",
                    "mediafire.com",
                    "mexashare.com",
                    "modsbase.com",
                    "mp4upload.com",
                    "prefiles.com",
                    "scribd.com",
                    "sendit.cloud", // sendit
                    "sharemods.com",

                    // Free stream hosts (一部)
                    "dailymotion.com",
                    "twitch.tv",
                    "soundcloud.com",
                    "vimeo.com",
                    "archive.org",
                    "rtve.es",
                    "rutube.ru",

                    // 以前のリストからの主要なもの
                    "dood.com",
                    "filefactory.com",
                    "pixeldrain.com",
                    "streamtape.com",
                    "uptobox.com",
                    "voe.sx",

                    // Alldebrid自身
                    "alldebrid.com"
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

    override fun onBackPressed() {
        // もしWebViewが履歴を戻れるなら、Webページを一つ戻る
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            // 戻る履歴がなければ、ブラウザ（アクティビティ）を閉じる
            super.onBackPressed()
        }
    }
}

// WebViewの状態をアプリ内で共有するためのシングルトンオブジェクト
object WebViewState {
    var lastUrl: String? = "https://www.google.com"
    var lastScrollY: Int = 0
}