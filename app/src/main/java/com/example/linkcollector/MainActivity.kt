package com.example.linkcollector

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.Response
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST


// --- Real-Debrid API の設定 ---
interface RealDebridApiService {
    @FormUrlEncoded
    @POST("unrestrict/link")
    suspend fun unlockLink(
        @Header("Authorization") authHeader: String,
        @Field("link") link: String
    ): Response<RdUnlockResponse>
}

data class RdUnlockResponse(
    val id: String?,
    val filename: String?,
    val download: String? // 変換後リンク
)

// --- メインのアクティビティ ---
class MainActivity : AppCompatActivity() {

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
    private lateinit var settingsButton: ImageButton

    private val realDebridApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.real-debrid.com/rest/1.0/")
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
            .build()
            .create(RealDebridApiService::class.java)
    }

    private val historyDao by lazy { AppDatabase.getDatabase(this).historyDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★ Edge-to-edge を有効化（ここで宣言しないと WindowInsets が取れない）
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_main)

        linksEditText = findViewById(R.id.linksEditText)
        pasteButton = findViewById(R.id.pasteButton)
        debridButton = findViewById(R.id.debridButton)
        copyButton = findViewById(R.id.copyButton)
        undoButton = findViewById(R.id.undoButton)
        openBrowserButton = findViewById(R.id.openBrowserButton)
        historyButton = findViewById(R.id.historyButton)
        messageTextView = findViewById(R.id.messageTextView)
        settingsButton = findViewById(R.id.settingsButton)

        // ★ ルートViewにシステムバー分のpadding を動的に適用
        val rootView = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // XMLの元のpadding(16dp)にシステムバー分を上乗せ
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            view.setPadding(
                dp16,
                systemBars.top + dp16,
                dp16,
                systemBars.bottom + dp16
            )
            WindowInsetsCompat.CONSUMED
        }

        settingsButton.setOnClickListener {
            showApiKeyDialog()
        }

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
            lastDebridedLinks = emptyList() // ←古い変換済みリンクの記憶もリセットしておくと安全です
            updateButtonState(false) // ←これでボタンが「CONVERT」に戻り、isShareModeも解除されます
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

    private fun getApiKey(): String {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return prefs.getString("rd_api_key", "") ?: ""
    }

    private fun saveApiKey(key: String) {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("rd_api_key", key).apply()
    }

    private fun showApiKeyDialog() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_api_key, null)
        dialog.setContentView(view)

        // 背景を角丸にする
        dialog.window?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundResource(android.R.color.transparent)

        val editText = view.findViewById<TextInputEditText>(R.id.apiKeyEditText)
        val saveButton = view.findViewById<Button>(R.id.saveButton)
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)

        editText.setText(getApiKey())

        saveButton.setOnClickListener {
            val key = editText.text.toString().trim()
            saveApiKey(key)
            showToast("APIキーを保存しました")
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun debridLinks() {
        val originalLinks = linksEditText.text.toString()
        val links = originalLinks.lines().filter { it.isNotBlank() }

        if (links.isEmpty()) {
            showToast("リンクが入力されていません")
            return
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            showToast("APIキーが設定されていません")
            showApiKeyDialog()
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
                        // 【変更点】realDebridApi を使い、ヘッダーに "Bearer キー" を入れる
                        realDebridApi.unlockLink(authHeader = "Bearer $apiKey", link = link)
                    }

                    if (response.isSuccessful) {
                        val body = response.body()
                        // 【変更点】Real-Debridの返り値のキー 'download' を使う
                        if (body?.download != null) {
                            debridedLinks.add(body.download)
                            val filename = body.filename ?: "unknown"
                            linksEditText.append("成功: $filename\n")

                            val historyItem = HistoryItem(
                                filename = filename,
                                premiumLink = body.download,
                                timestamp = System.currentTimeMillis()
                            )
                            withContext(Dispatchers.IO) {
                                historyDao.insert(historyItem)
                            }
                        } else {
                            linksEditText.append("失敗: リンク取得エラー\n")
                        }
                    } else {
                        if (response.code() == 401 || response.code() == 403) {
                            authenticationErrorOccurred = true
                            break
                        }
                        linksEditText.append("失敗: HTTPエラー ${response.code()}\n")
                    }
                } catch (e: Exception) {
                    linksEditText.append("失敗: 通信エラー\n")
                }
            }

            if (authenticationErrorOccurred) {
                showToast("Real-Debridの認証エラー。APIキーを確認してください。")
                linksEditText.setText(originalLinks)
                showApiKeyDialog()
            } else {
                val resultText = debridedLinks.joinToString("\n")
                linksEditText.setText(resultText)
                showToast("変換完了！")
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