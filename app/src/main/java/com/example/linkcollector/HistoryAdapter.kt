package com.example.linkcollector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// コンストラクタを MutableList で受け取るように変更
class HistoryAdapter(private val historyItems: MutableList<HistoryItem>) :
    RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    val selectedItems = mutableSetOf<HistoryItem>()

    class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val filenameTextView: TextView = itemView.findViewById(R.id.filenameTextView)
        val timestampTextView: TextView = itemView.findViewById(R.id.timestampTextView)
        val copyButton: ImageButton = itemView.findViewById(R.id.copyHistoryButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun getItemCount(): Int {
        return historyItems.size
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val currentItem = historyItems[position]
        holder.filenameTextView.text = currentItem.filename
        holder.timestampTextView.text = formatTimestamp(currentItem.timestamp)

        if (selectedItems.contains(currentItem)) {
            holder.itemView.setBackgroundColor(holder.itemView.context.getColor(R.color.list_item_selected_color))
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        holder.itemView.setOnClickListener {
            if (selectedItems.contains(currentItem)) {
                selectedItems.remove(currentItem)
            } else {
                selectedItems.add(currentItem)
            }
            notifyItemChanged(position)
        }

        holder.copyButton.setOnClickListener {
            val clipboard = holder.itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Premium Link", currentItem.premiumLink)
            clipboard.setPrimaryClip(clip)
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun clearData() {
        val size = historyItems.size
        if (size > 0) {
            historyItems.clear()
            selectedItems.clear()
            notifyItemRangeRemoved(0, size)
        }
    }
}