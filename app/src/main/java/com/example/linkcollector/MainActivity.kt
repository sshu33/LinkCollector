package com.example.linkcollector

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// --- Alldebrid API の設定 ---
interface AlldebridApiService {
    @GET("link/unlock")
    suspend fun unlockLink(
        @Query("agent") agent: String = "LinkCollectorApp",
        @Query("apikey") apikey: String, // ← ★コロンを削除しました！
        @Query("link") link: String
    ): UnlockResponse
}
data class UnlockResponse(val status: String, val data: LinkData?, val error: ApiError?)
data class LinkData(val link: String, val filename: String)
data class ApiError(val code: String, val message: String)

// --- メインのアクティビティ ---
class MainActivity : AppCompatActivity() {

    // あなたのAlldebridのAPIキー
    private val alldebridApiKey = "G12kZINsBcZI5wzK3NlT"

    private var lastDebridedLinks: List<String> = emptyList() // ★追加：最後に変換成功したリンクを保持
    private var isShareMode = false // ★追加：現在がShareモードかどうかを判断するフラグ

    private val browserActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val newLink = result.data?.getStringExtra("newLink")
            if (newLink != null) {
                addLinkToList(newLink)
            }
        }
    }

    private fun addLinkToList(link: String) {
        val currentText = linksEditText.text.toString()
        val newText = if (currentText.isEmpty()) link else "$currentText\n$link"
        linksEditText.setText(newText)
        linksEditText.setSelection(newText.length)
    }

    private lateinit var linksEditText: EditText
    private lateinit var pasteButton: ImageButton // ★変更
    private lateinit var debridButton: Button
    private lateinit var copyButton: ImageButton
    private lateinit var undoButton: ImageButton
    private lateinit var openBrowserButton: Button
    private lateinit var historyButton: ImageButton
    private lateinit var messageTextView: TextView

    private val alldebridApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.alldebrid.com/v4/")
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
            .build()
            .create(AlldebridApiService::class.java)
    }

    private val historyDao by lazy { AppDatabase.getDatabase(this).historyDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        linksEditText = findViewById(R.id.linksEditText)
        pasteButton = findViewById(R.id.pasteButton)
        debridButton = findViewById(R.id.debridButton)
        copyButton = findViewById(R.id.copyButton)
        undoButton = findViewById(R.id.undoButton)
        openBrowserButton = findViewById(R.id.openBrowserButton)
        historyButton = findViewById(R.id.historyButton)
        messageTextView = findViewById(R.id.messageTextView)

        setupButtonListeners()
    }

    private fun setupButtonListeners() {
        pasteButton.setOnClickListener { pasteFromClipboard() }

        debridButton.setOnClickListener {
            if (isShareMode) {
                // isShareMode（OPEN 1DMボタンのとき）は、メッセージ非表示で呼び出す
                openDownloader(showMessages = false)
            } else {
                debridLinks()
            }
        }

        debridButton.setOnLongClickListener {
            openDownloader(showMessages = false) // メッセージを非表示で呼び出す
            true
        }

        copyButton.setOnClickListener { copyToClipboard() }

        copyButton.setOnClickListener { copyToClipboard() }

        undoButton.setOnClickListener {
            val currentText = linksEditText.text.toString()
            if (currentText.isNotBlank()) {
                val newList = currentText.lines().dropLast(1)
                linksEditText.setText(newList.joinToString("\n"))
                linksEditText.setSelection(linksEditText.text.length)
            }
        }
        undoButton.setOnLongClickListener {
            linksEditText.setText("")
            true
        }

        openBrowserButton.setOnClickListener {
            val intent = Intent(this, BrowserActivity::class.java)
            browserActivityResultLauncher.launch(intent)
        }

        historyButton.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasteData = item?.text.toString()
            if (pasteData.isNotBlank()) {
                addLinkToList(pasteData)
                showToast("貼り付けました") // ←ここも統一
            } else {
                showToast("クリップボードが空です")
            }
        } else {
            showToast("クリップボードが空です")
        }
    }

    private fun debridLinks() {
        val originalLinks = linksEditText.text.toString()
        val links = originalLinks.lines().filter { it.isNotBlank() }
        if (links.isEmpty()) {
            showToast("リンクが入力されていません")
            return
        }
        if (alldebridApiKey.isBlank()) {
            showToast("APIキーが設定されていません")
            return
        }
        showToast("変換を開始します...")
        linksEditText.setText("変換中...\n\n")
        lifecycleScope.launch {
            val debridedLinks = mutableListOf<String>()
            var authenticationErrorOccurred = false
            for (link in links) {
                try {
                    val response = withContext(Dispatchers.IO) {
                        alldebridApi.unlockLink(apikey = alldebridApiKey, link = link)
                    }
                    if (response.status == "success" && response.data != null) {
                        debridedLinks.add(response.data.link)
                        linksEditText.append("成功: ${response.data.filename}\n")
                        val historyItem = HistoryItem(
                            filename = response.data.filename,
                            premiumLink = response.data.link,
                            timestamp = System.currentTimeMillis()
                        )
                        withContext(Dispatchers.IO) {
                            historyDao.insert(historyItem)
                        }
                    } else {
                        if (response.error?.code == "AUTH_BAD_APIKEY") {
                            authenticationErrorOccurred = true
                            break
                        }
                        val errorMessage = response.error?.message ?: "不明なエラー"
                        linksEditText.append("失敗: $errorMessage\n")
                    }
                } catch (e: Exception) {
                    linksEditText.append("失敗: ${e.message}\n")
                }
            }
            if (authenticationErrorOccurred) {
                showToast("Alldebridの認証が切れました")
                linksEditText.setText(originalLinks)
            } else {
                val resultText = debridedLinks.joinToString("\n")
                linksEditText.setText(resultText)
                showToast("変換完了！") // ←ここも統一
                lastDebridedLinks = debridedLinks
                updateButtonState(true)
            }
            linksEditText.setSelection(linksEditText.text.length)
        }
    }

    private fun copyToClipboard() {
        val textToCopy = linksEditText.text.toString()
        if (textToCopy.isNotBlank()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Debrided Links", textToCopy)
            clipboard.setPrimaryClip(clip)
            showToast("コピーしました！") // ←ここも統一
        } else {
            showToast("コピーする内容がありません")
        }
    }

    private fun showToast(message: String) {
        messageTextView.text = message
        messageTextView.visibility = View.VISIBLE

        // 2.5秒後にメッセージを非表示にする
        messageTextView.postDelayed({
            messageTextView.visibility = View.GONE
        }, 2500)
    }

    // updateButtonState メソッド
    private fun updateButtonState(isDownloaderMode: Boolean) {
        isShareMode = isDownloaderMode // isShareMode という変数名ですが、意味は同じなのでこのままでOK
        if (isShareMode) {
            debridButton.text = "OPEN 1DM" // ★"SHARE" から変更
        } else {
            debridButton.text = "CONVERT"
        }
    }

    private fun openDownloader(showMessages: Boolean = true) {
        if (lastDebridedLinks.isEmpty()) {
            val textToCopy = linksEditText.text.toString()
            if (textToCopy.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Debrided Links", textToCopy)
                clipboard.setPrimaryClip(clip)
                if (showMessages) {
                    showToast("テキストをコピーしました") // ←ここも統一
                }
            } else {
                if (showMessages) {
                    showToast("リンクがありません")
                }
                return
            }
        } else {
            val linksText = lastDebridedLinks.joinToString("\n")
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Debrided Links", linksText)
            clipboard.setPrimaryClip(clip)
            if (showMessages) {
                showToast("リンクをコピーしました！") // ←ここも統一
            }
        }
        val packageName = "idm.internet.download.manager"
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            if (showMessages) {
                showToast("1DM が見つかりません。")
            }
        }
        updateButtonState(false)
    }

    private fun showInAppMessage(message: String) {
        messageTextView.text = message
        messageTextView.visibility = View.VISIBLE

        // 2秒後にメッセージを非表示にする
        messageTextView.postDelayed({
            messageTextView.visibility = View.GONE
        }, 2000)
    }
}