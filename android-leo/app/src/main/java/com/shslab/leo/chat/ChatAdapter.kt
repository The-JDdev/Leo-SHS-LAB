package com.shslab.leo.chat

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shslab.leo.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ══════════════════════════════════════════
 *  LEO CHAT ADAPTER — SHS LAB
 *
 *  RecyclerView adapter for the chat interface.
 *  Two view types: USER (right-aligned bubble)
 *  and LEO (avatar + thinking accordion + response).
 *
 *  Thinking accordion:
 *  - Collapsed by default ("▶ Thinking... (N)")
 *  - Tap to expand/collapse the mini log console
 *  - All system events accumulate in the log while pending
 * ══════════════════════════════════════════
 */
class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER         = 0
        private const val TYPE_LEO          = 1
        const val PAYLOAD_THINKING          = "thinking_update"
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val messages    = mutableListOf<ChatMessage>()
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    // ──────────────────────────────────────────────────
    //  PUBLIC MUTATION API (all call on main thread)
    // ──────────────────────────────────────────────────

    /** Add a user message bubble to the chat. Returns the message id. */
    fun addUserMessage(text: String): String {
        val msg = ChatMessage(role = MessageRole.USER, text = text)
        runOnMain {
            messages.add(msg)
            notifyItemInserted(messages.lastIndex)
            scrollToBottom()
        }
        return msg.id
    }

    /**
     * Create a pending Leo bubble in "Thinking..." state.
     * @return the id to use for future appendThinkingLog / finalizeLeoMessage calls
     */
    fun beginLeoResponse(): String {
        val msg = ChatMessage(role = MessageRole.LEO, text = "", isPending = true)
        runOnMain {
            messages.add(msg)
            notifyItemInserted(messages.lastIndex)
            scrollToBottom()
        }
        return msg.id
    }

    /**
     * Append a single log line to the thinking accordion of a pending Leo message.
     * Thread-safe: can be called from any thread.
     */
    fun appendThinkingLog(id: String, log: String) {
        val idx = indexById(id) ?: return
        val msg = messages[idx]
        synchronized(msg.thinkingLogs) {
            msg.thinkingLogs.add(log)
        }
        // Debounced: post once per 80ms to avoid hammering the UI
        mainHandler.post {
            val currentIdx = indexById(id) ?: return@post
            notifyItemChanged(currentIdx, PAYLOAD_THINKING)
        }
    }

    /**
     * Finalize a pending Leo message: set its text and mark it complete.
     * This hides "Thinking..." state and shows the real response bubble.
     */
    fun finalizeLeoMessage(id: String, text: String) {
        runOnMain {
            val idx = indexById(id) ?: return@runOnMain
            val msg = messages[idx]
            msg.text      = text
            msg.isPending = false
            notifyItemChanged(idx)
            scrollToBottom()
        }
    }

    // ──────────────────────────────────────────────────
    //  RECYCLER ADAPTER OVERRIDES
    // ──────────────────────────────────────────────────

    override fun getItemViewType(position: Int): Int =
        if (messages[position].role == MessageRole.USER) TYPE_USER else TYPE_LEO

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserViewHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            else      -> LeoViewHolder(inflater.inflate(R.layout.item_chat_leo, parent, false))
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val msg = messages[position]
        if (payloads.contains(PAYLOAD_THINKING) && holder is LeoViewHolder) {
            holder.updateThinkingLogs(msg)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is UserViewHolder -> holder.bind(msg)
            is LeoViewHolder  -> holder.bind(msg)
        }
    }

    override fun getItemCount(): Int = messages.size

    // ──────────────────────────────────────────────────
    //  VIEW HOLDERS
    // ──────────────────────────────────────────────────

    inner class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage   = itemView.findViewById<TextView>(R.id.tvUserMessage)
        private val tvTimestamp = itemView.findViewById<TextView>(R.id.tvUserTimestamp)

        fun bind(msg: ChatMessage) {
            tvMessage.text   = msg.text
            tvTimestamp.text = timeFmt.format(Date(msg.timestamp))
        }
    }

    inner class LeoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val layoutThinking   = itemView.findViewById<View>(R.id.layoutThinking)
        private val tvThinkingHeader = itemView.findViewById<TextView>(R.id.tvThinkingHeader)
        private val tvThinkingLogs   = itemView.findViewById<TextView>(R.id.tvThinkingLogs)
        private val tvMessage        = itemView.findViewById<TextView>(R.id.tvLeoMessage)
        private val tvTimestamp      = itemView.findViewById<TextView>(R.id.tvLeoTimestamp)

        fun bind(msg: ChatMessage) {
            // Always show thinking block (has the log history)
            layoutThinking.visibility = View.VISIBLE
            updateThinkingLogs(msg)

            // Accordion expand/collapse toggle
            tvThinkingHeader.setOnClickListener {
                msg.isThinkingExpanded = !msg.isThinkingExpanded
                applyExpansion(msg)
            }

            // Response bubble — show only when not pending
            if (!msg.isPending && msg.text.isNotBlank()) {
                tvMessage.text       = msg.text
                tvMessage.visibility = View.VISIBLE
            } else {
                tvMessage.visibility = View.GONE
            }

            tvTimestamp.text = timeFmt.format(Date(msg.timestamp))
            applyExpansion(msg)
        }

        fun updateThinkingLogs(msg: ChatMessage) {
            val logCount = synchronized(msg.thinkingLogs) { msg.thinkingLogs.size }
            val logText  = synchronized(msg.thinkingLogs) { msg.thinkingLogs.joinToString("\n") }

            val arrowChar = if (msg.isThinkingExpanded) "▼" else "▶"
            val stateLabel = if (msg.isPending) "Thinking..." else "Thought"
            tvThinkingHeader.text = "$arrowChar  $stateLabel ($logCount steps)"

            tvThinkingLogs.text = logText
            applyExpansion(msg)
        }

        private fun applyExpansion(msg: ChatMessage) {
            tvThinkingLogs.visibility = if (msg.isThinkingExpanded) View.VISIBLE else View.GONE
            val arrowChar = if (msg.isThinkingExpanded) "▼" else "▶"
            val stateLabel = if (msg.isPending) "Thinking..." else "Thought"
            val logCount = synchronized(msg.thinkingLogs) { msg.thinkingLogs.size }
            tvThinkingHeader.text = "$arrowChar  $stateLabel ($logCount steps)"
        }
    }

    // ──────────────────────────────────────────────────
    //  INTERNAL HELPERS
    // ──────────────────────────────────────────────────

    private fun indexById(id: String): Int? {
        val idx = messages.indexOfFirst { it.id == id }
        return if (idx >= 0) idx else null
    }

    private fun scrollToBottom() {
        recyclerView?.post {
            val count = itemCount
            if (count > 0) recyclerView?.smoothScrollToPosition(count - 1)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    fun clear() {
        runOnMain {
            val size = messages.size
            messages.clear()
            notifyItemRangeRemoved(0, size)
        }
    }
}
