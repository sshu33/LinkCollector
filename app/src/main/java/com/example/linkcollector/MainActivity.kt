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

// 結果の中で、失敗したリンクの前に入れる区切りの行
private const val FAILED_MARK = "--- 失敗 ---"

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
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://www.storage-portal.com/api/external/v1/")
            .client(client)
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

        migrateOldKey()

        settingsButton.setOnClickListener {
            showServiceDialog()
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

    private fun prefs() = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

    // 使用中のサービス（"rd" = Real-Debrid / "fd" = File Debrid）
    private fun getService(): String = prefs().getString("service", "rd") ?: "rd"

    private fun setService(service: String) {
        prefs().edit().putString("service", service).apply()
    }

    private fun keyName(service: String) = if (service == "fd") "fd_api_key" else "rd_api_key"

    private fun serviceLabel(service: String) = if (service == "fd") "File Debrid" else "Real-Debrid"

    // 使用中のサービスのキー
    private fun getApiKey(): String = getApiKey(getService())

    private fun getApiKey(service: String): String =
        prefs().getString(keyName(service), "") ?: ""

    // 使用中のサービスのキーとして保存
    private fun saveApiKey(key: String) {
        prefs().edit().putString(keyName(getService()), key).apply()
    }

    // 以前 Real-Debrid の欄に File Debrid のキー（obb_）を入れていた場合は File Debrid 側へ移す
    private fun migrateOldKey() {
        val old = prefs().getString("rd_api_key", "") ?: ""
        if (old.startsWith("obb_")) {
            prefs().edit()
                .putString("fd_api_key", old)
                .putString("rd_api_key", "")
                .putString("service", "fd")
                .apply()
        }
    }

    // 設定ボタン：使うサービスを選ぶ
    private fun showServiceDialog() {
        val services = arrayOf("rd", "fd")
        val labels = arrayOf("Real-Debrid", "File Debrid")
        var selected = services.indexOf(getService()).coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("使用するサービス")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton("決定") { _, _ ->
                setService(services[selected])
                showToast("${labels[selected]} に切り替えました")
            }
            .setNeutralButton("APIキーを設定") { _, _ ->
                setService(services[selected])
                showApiKeyDialog()
            }
            .setNegativeButton("キャンセル", null)
            .show()
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

        (editText.parent?.parent as? com.google.android.material.textfield.TextInputLayout)
            ?.hint = "${serviceLabel(getService())} のAPIキー"
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
        val links = originalLinks.lines()
            .filter { it.isNotBlank() && it.trim() != FAILED_MARK }

        if (links.isEmpty()) {
            showToast("リンクが入力されていません")
            return
        }

        startConversion(getService(), links, emptyList(), originalLinks, allowSwitch = true)
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

    private val hideMessage = Runnable { messageTextView.visibility = View.GONE }

    private fun showToast(message: String, durationMs: Long = 2500) {
        messageTextView.text = message
        messageTextView.visibility = View.VISIBLE

        // 前のメッセージを消す予約を取り消してから、新しく予約する
        messageTextView.removeCallbacks(hideMessage)
        messageTextView.postDelayed(hideMessage, durationMs)
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
        val packageName = "idm.internet.download.manager.plus"
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

    // --- 変換の本体（Real-Debrid / File Debrid 共通） ---
    private fun startConversion(
        service: String,
        links: List<String>,
        keepSuccess: List<String>,
        restoreText: String,
        allowSwitch: Boolean
    ) {
        val label = serviceLabel(service)
        val apiKey = getApiKey(service)
        if (apiKey.isBlank()) {
            showToast("${label}のAPIキーが設定されていません")
            if (service == getService()) showApiKeyDialog()
            return
        }

        showToast("$label で変換を開始します...")

        lifecycleScope.launch {
            val total = links.size
            val results = arrayOfNulls<FdResult>(total)
            val log = StringBuilder()
            var done = 0

            // 1件終わるごとに進み具合を表示
            fun record(i: Int, r: FdResult) {
                results[i] = r
                done++
                if (r.download != null) {
                    log.append("成功: ${r.filename ?: "unknown"}\n")
                } else if (!r.authError) {
                    log.append("失敗: ${r.error}\n")
                }
                linksEditText.setText("$label で変換中... $done/$total\n\n$log")
            }

            linksEditText.setText("$label で変換中... 0/$total\n\n")

            if (service == "fd") {
                // File Debrid：ホストごとに FD_PARALLEL 件ずつ同時に変換
                val semaphores = links.map { fdHostKey(it) }.distinct()
                    .associateWith { Semaphore(FD_PARALLEL) }
                coroutineScope {
                    links.forEachIndexed { i, link ->
                        launch {
                            semaphores.getValue(fdHostKey(link)).withPermit {
                                record(i, fileDebridWithRetry(apiKey, link))
                            }
                        }
                    }
                }
            } else {
                // Real-Debrid：今までどおり1件ずつ順番に変換
                for ((i, link) in links.withIndex()) {
                    val r = realDebridUnlock(apiKey, link)
                    record(i, r)
                    if (r.authError) break
                }
            }

            if (results.any { it?.authError == true }) {
                showToast("${label}の認証エラー。APIキーを確認してください。")
                linksEditText.setText(restoreText)
                linksEditText.setSelection(linksEditText.text.length)
                if (service == getService()) showApiKeyDialog()
                return@launch
            }

            // 結果は元のリンクの順番で並べる
            val newSuccess = mutableListOf<FdResult>()
            val failedLinks = mutableListOf<String>()
            val failures = mutableListOf<String>()
            links.forEachIndexed { i, link ->
                val r = results[i]
                if (r?.download != null) {
                    newSuccess.add(r)
                } else {
                    failedLinks.add(link)
                    failures.add("$link\n→ ${r?.error ?: "未処理"}")
                }
            }

            withContext(Dispatchers.IO) {
                newSuccess.forEach { r ->
                    val dl = r.download ?: return@forEach
                    historyDao.insert(
                        HistoryItem(
                            filename = r.filename ?: "unknown",
                            premiumLink = dl,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }

            val successLinks = keepSuccess + newSuccess.mapNotNull { it.download }
            showResult(successLinks, failedLinks)
            val doneMsg = if (failedLinks.isEmpty()) "変換完了！"
            else "変換完了（失敗 ${failedLinks.size}件）"
            showToast(doneMsg)

            // File Debrid のときは残量を取得して表示（失敗のダイアログは待たせない）
            if (service == "fd") {
                lifecycleScope.launch {
                    val left = fetchFileDebridLeft()
                    if (left != null) showToast("$doneMsg　残り $left", 6000)
                }
            }

            if (failures.isNotEmpty()) {
                showFailureDialog(service, failures, failedLinks, successLinks, allowSwitch)
            }
        }
    }

    // Real-Debrid で1件変換する（結果の形は File Debrid と同じ FdResult を使う）
    private suspend fun realDebridUnlock(apiKey: String, link: String): FdResult =
        withContext(Dispatchers.IO) {
            try {
                val response = realDebridApi.unlockLink(authHeader = "Bearer $apiKey", link = link)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.download != null) {
                        FdResult(download = body.download, filename = body.filename)
                    } else {
                        FdResult(error = "リンク取得エラー")
                    }
                } else if (response.code() == 401 || response.code() == 403) {
                    FdResult(authError = true)
                } else {
                    val raw = response.errorBody()?.string()?.take(150)
                    FdResult(error = "HTTPエラー ${response.code()}" + (raw?.let { ": $it" } ?: ""))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FdResult(error = "通信エラー: ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
        }

    // 成功したリンクを上に、失敗したリンクを区切りの下に並べる
    private fun showResult(successLinks: List<String>, failedLinks: List<String>) {
        val sb = StringBuilder(successLinks.joinToString("\n"))
        if (failedLinks.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(FAILED_MARK).append("\n").append(failedLinks.joinToString("\n"))
        }
        linksEditText.setText(sb.toString())
        linksEditText.setSelection(linksEditText.text.length)
        lastDebridedLinks = successLinks
        updateButtonState(successLinks.isNotEmpty())
    }

    // 失敗の理由の一覧（もう一方のサービスで変換し直すボタン付き）
    private fun showFailureDialog(
        service: String,
        failures: List<String>,
        failedLinks: List<String>,
        successLinks: List<String>,
        allowSwitch: Boolean
    ) {
        val text = failures.joinToString("\n\n")
        val other = if (service == "fd") "rd" else "fd"

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("失敗 ${failures.size}件")
            .setMessage(text)
            .setNegativeButton("閉じる", null)
            .setNeutralButton("コピー") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Errors", text))
            }

        if (allowSwitch) {
            builder.setPositiveButton("${serviceLabel(other)}で変換") { _, _ ->
                startConversion(
                    other,
                    failedLinks,
                    successLinks,
                    linksEditText.text.toString(),
                    allowSwitch = false
                )
            }
        }
        builder.show()
    }

    // File Debrid の残量を「21.3GB」のような文字で返す（取得できなければ null）
    private suspend fun fetchFileDebridLeft(): String? {
        val apiKey = getApiKey("fd")
        if (apiKey.isBlank()) return null
        return try {
            val res = withContext(Dispatchers.IO) { fileDebridApi.account("Bearer $apiKey") }
            val body = res.body() ?: return null
            if (!body.isJsonObject) return null
            val left = body.asJsonObject.unwrapData().get("trafficleft")?.asLong ?: return null
            formatBytes(left)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / 1_073_741_824.0
        return if (gb >= 1) {
            String.format(java.util.Locale.US, "%.1fGB", gb)
        } else {
            String.format(java.util.Locale.US, "%.0fMB", bytes / 1_048_576.0)
        }
    }


    // リンクのホストをまとめる（katfile.biz と katfile.com などを同じホストとして数える）
    private fun fdHostKey(link: String): String {
        val host = runCatching { java.net.URI(link.trim()).host }.getOrNull()
            ?.lowercase()?.removePrefix("www.") ?: return "other"
        if (host == "rg.to") return "rapidgator"
        val known = listOf("rapidgator", "katfile", "dailyuploads", "turbobit", "frdl", "uploady")
        return known.firstOrNull { host.contains(it) } ?: host
    }

    // status 55（同時実行数の上限）とタイムアウトのときは待ってから再試行する
    private suspend fun fileDebridWithRetry(apiKey: String, link: String): FdResult {
        val waits = listOf(10_000L, 20_000L, 40_000L)
        var result = fileDebridSafe(apiKey, link)
        for (w in waits) {
            val err = result.error ?: break
            val retry = err.contains("（status 55）") || err.contains("SocketTimeoutException")
            if (!retry) break
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
        val apiKey = getApiKey("fd")
        if (apiKey.isBlank()) {
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