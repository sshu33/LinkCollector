package com.example.linkcollector

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit


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

// --- File Debrid API の設定（お試し） ---
interface FileDebridApiService {
    @POST("unrestrict")
    suspend fun unrestrict(
        @Header("Authorization") authHeader: String,
        @Body body: Map<String, String>
    ): Response<JsonObject>

    @GET("link/{linkId}")
    suspend fun getLink(
        @Header("Authorization") authHeader: String,
        @Path("linkId") linkId: String
    ): Response<JsonObject>

    @GET("hosts")
    suspend fun hosts(
        @Header("Authorization") authHeader: String
    ): Response<JsonElement>

    @GET("account")
    suspend fun account(
        @Header("Authorization") authHeader: String
    ): Response<JsonElement>
}

data class FdResult(
    val download: String? = null,
    val filename: String? = null,
    val error: String? = null,
    val authError: Boolean = false
)

// File Debrid で同時に変換する数（上限が分かったら書き換える）
private const val FD_PARALLEL = 3

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

    private val fileDebridApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://www.storage-portal.com/api/external/v1/")
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
            .build()
            .create(FileDebridApiService::class.java)
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

        settingsButton.setOnLongClickListener {
            checkFileDebridLimits()
            true
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
            // ▼File Debrid は同時変換で処理
            if (apiKey.startsWith("obb_")) {
                fileDebridBatch(apiKey, links, originalLinks)
                return@launch
            }

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

    // --- File Debrid の変換（お試し） ---
    private suspend fun fileDebridUnlock(apiKey: String, link: String): FdResult =
        withContext(Dispatchers.IO) {
            val tag = "FileDebrid"
            val auth = "Bearer $apiKey"

            val first = fileDebridApi.unrestrict(auth, mapOf("url" to link))
            if (first.code() == 401) return@withContext FdResult(authError = true)
            if (!first.isSuccessful) {
                val raw = first.errorBody()?.string()
                Log.d(tag, "unrestrict エラー ${first.code()}: $raw")
                return@withContext FdResult(error = fdErrorText(first.code(), raw))
            }
            var json = first.body()?.unwrapData()
                ?: return@withContext FdResult(error = "空のレスポンス")
            Log.d(tag, "unrestrict: $json")

            val linkId = json.str("linkId") ?: json.str("link_id") ?: json.str("id")
            val deadline = System.currentTimeMillis() + 180_000

            while (!json.bool("download_ready")) {
                if (linkId == null) return@withContext FdResult(error = "ID不明: $json")
                if (System.currentTimeMillis() > deadline) {
                    return@withContext FdResult(error = "タイムアウト")
                }
                val wait = runCatching { json.get("poll_after_seconds").asLong }.getOrNull() ?: 3L
                delay(wait.coerceIn(1, 30) * 1000)

                val res = fileDebridApi.getLink(auth, linkId)
                if (!res.isSuccessful) {
                    val raw = res.errorBody()?.string()
                    Log.d(tag, "link エラー ${res.code()}: $raw")
                    return@withContext FdResult(error = fdErrorText(res.code(), raw))
                }
                json = res.body()?.unwrapData()
                    ?: return@withContext FdResult(error = "空のレスポンス")
                Log.d(tag, "link: $json")
            }

            FdResult(
                download = json.str("link"),
                filename = json.str("filename") ?: json.str("name"),
                error = if (json.str("link") == null) "リンクなし: $json" else null
            )
        }

    // 中身が "data" の内側に入っている形式に対応
    private fun JsonObject.unwrapData(): JsonObject {
        val d = get("data")
        return if (d != null && d.isJsonObject) d.asJsonObject else this
    }

    // エラーの中身から status と msg を取り出す（JSONでなければ先頭100文字）
    private fun fdErrorText(code: Int, raw: String?): String {
        val json = runCatching {
            GsonBuilder().create().fromJson(raw, JsonObject::class.java)
        }.getOrNull()
        val status = json?.str("status")
        val msg = json?.str("msg")
        return if (msg != null) {
            "HTTP $code（status $status）: $msg"
        } else {
            "HTTP $code: ${raw?.take(100) ?: "内容なし"}"
        }
    }

    private fun JsonObject.str(key: String): String? {
        val e = get(key) ?: return null
        return if (e.isJsonPrimitive) e.asString else null
    }

    private fun JsonObject.bool(key: String): Boolean =
        runCatching { get(key).asBoolean }.getOrDefault(false)

    // --- File Debrid の一括変換（同時実行） ---
    private suspend fun fileDebridBatch(apiKey: String, links: List<String>, originalLinks: String) {
        val semaphore = Semaphore(FD_PARALLEL)
        val results = arrayOfNulls<FdResult>(links.size)

        coroutineScope {
            links.forEachIndexed { i, link ->
                launch {
                    semaphore.withPermit {
                        val r = fileDebridWithRetry(apiKey, link)
                        results[i] = r
                        if (r.download != null) {
                            linksEditText.append("成功: ${r.filename ?: "unknown"}\n")
                        } else if (!r.authError) {
                            linksEditText.append("失敗: ${r.error}\n")
                        }
                    }
                }
            }
        }

        if (results.any { it?.authError == true }) {
            showToast("File Debridの認証エラー。APIキーを確認してください。")
            linksEditText.setText(originalLinks)
            showApiKeyDialog()
            return
        }

        // 結果は元のリンクの順番で並べる
        val debridedLinks = mutableListOf<String>()
        val failures = mutableListOf<String>()
        links.forEachIndexed { i, link ->
            val r = results[i]
            if (r?.download != null) {
                debridedLinks.add(r.download)
            } else {
                failures.add("$link\n→ ${r?.error}")
            }
        }

        withContext(Dispatchers.IO) {
            results.forEach { r ->
                if (r?.download != null) {
                    historyDao.insert(
                        HistoryItem(
                            filename = r.filename ?: "unknown",
                            premiumLink = r.download,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        linksEditText.setText(debridedLinks.joinToString("\n"))
        showToast("変換完了！")
        lastDebridedLinks = debridedLinks
        updateButtonState(true)

        if (failures.isNotEmpty()) {
            val text = failures.joinToString("\n\n")
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("失敗 ${failures.size}件")
                .setMessage(text)
                .setPositiveButton("閉じる", null)
                .setNeutralButton("コピー") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Errors", text))
                }
                .show()
        }
        linksEditText.setSelection(linksEditText.text.length)
    }

    // status 55（同時実行数の上限）のときは待ってから再試行する
    private suspend fun fileDebridWithRetry(apiKey: String, link: String): FdResult {
        val waits = listOf(10_000L, 20_000L, 40_000L)
        var result = fileDebridSafe(apiKey, link)
        for (w in waits) {
            if (result.error?.contains("（status 55）") != true) break
            delay(w)
            result = fileDebridSafe(apiKey, link)
        }
        return result
    }

    private suspend fun fileDebridSafe(apiKey: String, link: String): FdResult =
        try {
            fileDebridUnlock(apiKey, link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FdResult(error = "通信エラー: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }

    // --- 確認用：ホストごとの上限を表示（確認が終わったら消してOK） ---
    private fun checkFileDebridLimits() {
        val apiKey = getApiKey()
        if (!apiKey.startsWith("obb_")) {
            showToast("File DebridのAPIキーを設定してください")
            return
        }
        showToast("上限を確認中...")

        lifecycleScope.launch {
            val auth = "Bearer $apiKey"
            val words = listOf("rapidgator", "katfile", "dailyuploads")
            val sb = StringBuilder()
            try {
                val hostsRes = withContext(Dispatchers.IO) { fileDebridApi.hosts(auth) }
                val accountRes = withContext(Dispatchers.IO) { fileDebridApi.account(auth) }
                val sources = listOf("/hosts" to hostsRes, "/account" to accountRes)

                for ((name, res) in sources) {
                    sb.append("■ $name（HTTP ${res.code()}）\n")
                    val body = res.body()
                    if (body == null) {
                        sb.append(res.errorBody()?.string()?.take(300) ?: "内容なし").append("\n\n")
                        continue
                    }
                    var foundAny = false
                    for (w in words) {
                        val found = mutableListOf<String>()
                        findByHost(body, w, found)
                        sb.append("[$w]\n")
                        if (found.isEmpty()) {
                            sb.append("見つからず\n")
                        } else {
                            foundAny = true
                            found.take(3).forEach { sb.append(it.take(500)).append("\n") }
                        }
                    }
                    if (!foundAny) sb.append("先頭: ").append(body.toString().take(400)).append("\n")
                    sb.append("\n")
                }
            } catch (e: Exception) {
                sb.append("通信エラー: ${e.javaClass.simpleName} ${e.message ?: ""}")
            }

            val text = sb.toString()
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("File Debrid の上限")
                .setMessage(text)
                .setPositiveButton("閉じる", null)
                .setNeutralButton("コピー") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Limits", text))
                }
                .show()
        }
    }

    // 応答の中から、ホスト名を含む部分だけを探す
    private fun findByHost(e: JsonElement, word: String, out: MutableList<String>) {
        if (e.isJsonArray) {
            e.asJsonArray.forEach { findByHost(it, word, out) }
            return
        }
        if (!e.isJsonObject) return
        val obj = e.asJsonObject
        val hasValue = obj.entrySet().any { (_, v) ->
            v.isJsonPrimitive && v.asString.lowercase().contains(word)
        }
        if (hasValue) {
            out.add(obj.toString())
            return
        }
        for ((k, v) in obj.entrySet()) {
            if (k.lowercase().contains(word)) out.add("$k: $v") else findByHost(v, word, out)
        }
    }
}