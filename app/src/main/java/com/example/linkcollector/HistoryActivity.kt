package com.example.linkcollector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class HistoryActivity : AppCompatActivity() {

    private val historyDao by lazy { AppDatabase.getDatabase(this).historyDao() }
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var copySelectedButton: Button
    private lateinit var deleteAllButton: ImageButton

    private var isDownloaderMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_history)

        val rootView = findViewById<LinearLayout>(R.id.historyRootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            view.setPadding(dp16, systemBars.top + dp16, dp16, systemBars.bottom + dp16)
            WindowInsetsCompat.CONSUMED
        }

        copySelectedButton = findViewById(R.id.copySelectedButton)
        deleteAllButton = findViewById(R.id.deleteAllButton)
        val historyRecyclerView: RecyclerView = findViewById(R.id.historyRecyclerView)
        historyRecyclerView.layoutManager = LinearLayoutManager(this)

        setupButtonListeners()
        loadHistory()
    }

    private fun setupButtonListeners() {
        // 通常クリックの処理
        copySelectedButton.setOnClickListener {
            if (isDownloaderMode) {
                // 1DM起動モードの時
                openDownloader(showToast = true)
            } else {
                // 通常のコピーモードの時
                copySelectedItems()
            }
        }

        // 長押しの処理
        copySelectedButton.setOnLongClickListener {
            // ★★★ 選択アイテムの有無をチェックせずに、直接1DMを起動 ★★★
            openDownloader(showToast = false)
            true // イベントを消費
        }

        deleteAllButton.setOnClickListener {
            showDeleteConfirmDialog()
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val historyList = historyDao.getAllHistory()
            withContext(Dispatchers.Main) {
                historyAdapter = HistoryAdapter(historyList.toMutableList())
                val historyRecyclerView: RecyclerView = findViewById(R.id.historyRecyclerView)
                historyRecyclerView.adapter = historyAdapter
            }
        }
    }

    private fun showDeleteConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("履歴の削除")
            .setMessage("本当にすべての履歴を削除しますか？\nこの操作は元に戻せません。")
            .setPositiveButton("はい") { _, _ ->
                deleteAllHistory()
            }
            .setNegativeButton("いいえ", null)
            .show()
    }

    private fun deleteAllHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            historyDao.deleteAll()
            withContext(Dispatchers.Main) {
                if (::historyAdapter.isInitialized) {
                    historyAdapter.clearData()
                }
                Toast.makeText(this@HistoryActivity, "すべての履歴を削除しました", Toast.LENGTH_SHORT).show()
                updateButtonState(false)
            }
        }
    }

    // 通常クリックで呼ばれるメソッド
    private fun copySelectedItems() {
        if (!::historyAdapter.isInitialized || historyAdapter.selectedItems.isEmpty()) {
            Toast.makeText(this, "コピーする項目が選択されていません", Toast.LENGTH_SHORT).show()
            return
        }

        // 選択されたリンクをクリップボードにコピー
        val selectedLinks = historyAdapter.selectedItems
            .map { it.premiumLink }
            .joinToString("\n")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Selected Premium Links", selectedLinks)
        clipboard.setPrimaryClip(clip)

        updateButtonState(true)
    }

    // ★★★ copySelectedAndOpenDownloader() は不要になったので削除しました ★★★

    private fun updateButtonState(isDownloader: Boolean) {
        isDownloaderMode = isDownloader
        if (isDownloader) {
            copySelectedButton.text = "OPEN 1DM"
        } else {
            copySelectedButton.text = "Copy Selected"
        }
    }

    // ★★★ openDownloaderを少し変更 ★★★
    private fun openDownloader(showToast: Boolean = true) {
        // 1DM起動前に、選択中のアイテムがあればそれをコピーする
        if (::historyAdapter.isInitialized && historyAdapter.selectedItems.isNotEmpty()) {
            val selectedLinks = historyAdapter.selectedItems
                .map { it.premiumLink }
                .joinToString("\n")
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Selected Premium Links", selectedLinks)
            clipboard.setPrimaryClip(clip)
        }

        // --- ここから下は1DMを起動する処理 ---
        val packageName = "idm.internet.download.manager"
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            if(showToast) {
                Toast.makeText(this, "1DMを起動します", Toast.LENGTH_SHORT).show()
            }
        } else {
            if(showToast) {
                Toast.makeText(this, "1DM が見つかりません", Toast.LENGTH_SHORT).show()
            }
        }
        updateButtonState(false)
    }
}