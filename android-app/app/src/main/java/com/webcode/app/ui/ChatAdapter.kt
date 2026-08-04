package com.webcode.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.webcode.app.R
import com.webcode.app.api.Part
import com.webcode.app.api.SessionMessage
import org.json.JSONObject

interface ChatListener {
    fun onApprove(part: Part.Tool)
    fun onReject(part: Part.Tool)
    fun onAnswer(part: Part.Tool, answer: String)
}

class ChatAdapter(
    private val ctx: Context,
    private val listener: ChatListener,
    private val messages: MutableList<SessionMessage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var statusText: String? = null
    val expanded = mutableSetOf<String>()

    private data class Row(
        val kind: Int,
        val messageIndex: Int,
        val partIndex: Int
    )

    private val rows = mutableListOf<Row>()

    companion object {
        const val TYPE_USER = 0
        const val TYPE_TEXT = 1
        const val TYPE_THINKING = 2
        const val TYPE_TOOL = 3
        const val TYPE_STATUS = 4
    }

    private val codeBg = ContextCompat.getColor(ctx, R.color.panel3)
    private val codeFg = ContextCompat.getColor(ctx, R.color.success)

    fun submit(list: List<SessionMessage> = messages) {
        rebuild()
    }

    fun clear() {
        statusText = null
        rebuild()
    }

    fun setStatus(s: String?) {
        statusText = s
        rebuild()
    }

    fun appendDelta(messageId: String, text: String) {
        val m = messages.find { it.id == messageId }
        if (m != null) {
            val last = m.parts.lastOrNull()
            if (last is Part.Text) {
                last.text += text
            } else {
                m.parts.add(Part.Text(text))
            }
        }
        rebuild()
    }

    fun appendThinkingDelta(messageId: String, text: String) {
        val m = messages.find { it.id == messageId }
        if (m != null) {
            val last = m.parts.lastOrNull()
            if (last is Part.Thinking) {
                last.text += text
            } else {
                m.parts.add(Part.Thinking(text))
            }
        }
        rebuild()
    }

    fun addTool(messageId: String, part: Part.Tool) {
        val m = messages.find { it.id == messageId }
        if (m != null) m.parts.add(part)
        rebuild()
    }

    fun replaceIds(localUserId: String, serverUserId: String, localAssistantId: String, serverAssistantId: String) {
        for (m in messages) {
            if (m.id == localUserId) m.id = serverUserId
            if (m.id == localAssistantId) m.id = serverAssistantId
        }
        rebuild()
    }

    fun findMessage(messageId: String): SessionMessage? = messages.find { it.id == messageId }

    private fun rebuild() {
        rows.clear()
        for (mi in messages.indices) {
            val m = messages[mi]
            if (m.role == "user") {
                rows.add(Row(TYPE_USER, mi, -1))
            } else {
                for (pi in m.parts.indices) {
                    val p = m.parts[pi]
                    rows.add(
                        Row(
                            when (p) {
                                is Part.Text -> TYPE_TEXT
                                is Part.Thinking -> TYPE_THINKING
                                is Part.Tool -> TYPE_TOOL
                            },
                            mi,
                            pi
                        )
                    )
                }
            }
        }
        if (statusText != null) rows.add(Row(TYPE_STATUS, -1, -1))
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = rows[position].kind

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> VH(inf.inflate(R.layout.item_user, parent, false))
            TYPE_TEXT -> VH(inf.inflate(R.layout.item_text, parent, false))
            TYPE_THINKING -> ThinkingVH(inf.inflate(R.layout.item_thinking, parent, false))
            TYPE_TOOL -> ToolVH(inf.inflate(R.layout.item_tool, parent, false))
            else -> StatusVH(inf.inflate(R.layout.item_status, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when (holder) {
            is ThinkingVH -> {
                val m = messages[row.messageIndex]
                val p = m.parts[row.partIndex] as Part.Thinking
                holder.text.text = p.text
                val key = "${m.id}:${row.partIndex}"
                val open = expanded.contains(key)
                holder.text.visibility = if (open) View.VISIBLE else View.GONE
                holder.header.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    ContextCompat.getDrawable(ctx, R.drawable.ic_chevron),
                    null, null, null
                )
                holder.header.compoundDrawables[0]?.setTint(
                    if (open) ContextCompat.getColor(ctx, R.color.accent)
                    else ContextCompat.getColor(ctx, R.color.muted)
                )
                holder.root.setOnClickListener { toggleThinking(key) }
            }
            is ToolVH -> {
                val m = messages[row.messageIndex]
                val p = m.parts[row.partIndex] as Part.Tool
                holder.bind(p, "${m.id}:${row.partIndex}")
            }
            is StatusVH -> {
                holder.text.text = statusText ?: ""
            }
            else -> {
                val m = messages[row.messageIndex]
                if (m.role == "user") {
                    (holder.itemView.findViewById<TextView>(R.id.user_text)).text =
                        (m.parts.firstOrNull() as? Part.Text)?.text ?: ""
                } else {
                    val p = m.parts[row.partIndex] as Part.Text
                    MarkwonRenderer.setMarkdown(
                        holder.itemView.findViewById(R.id.assistant_text),
                        p.text
                    )
                }
            }
        }
    }

    private fun toggleThinking(key: String) {
        if (expanded.contains(key)) expanded.remove(key) else expanded.add(key)
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view)

    inner class ThinkingVH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.thinking_root)
        val text: TextView = view.findViewById(R.id.thinking_text)
        val header: TextView = view.findViewById(R.id.thinking_header)
    }

    inner class StatusVH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.status_text)
    }

    inner class ToolVH(view: View) : RecyclerView.ViewHolder(view) {
        private val root: LinearLayout = view.findViewById(R.id.tool_root)
        private val spinner: ProgressBar = view.findViewById(R.id.tool_spinner)
        private val icon: ImageView = view.findViewById(R.id.tool_icon)
        private val title: TextView = view.findViewById(R.id.tool_title)
        private val stateText: TextView = view.findViewById(R.id.tool_state)
        private val chevron: ImageView = view.findViewById(R.id.tool_chevron)
        private val detail: TextView = view.findViewById(R.id.tool_detail)
        private val approvalRow: LinearLayout = view.findViewById(R.id.approval_row)
        private val approvalCommand: TextView = view.findViewById(R.id.approval_command)
        private val approveBtn: Button = view.findViewById(R.id.approve_btn)
        private val rejectBtn: Button = view.findViewById(R.id.reject_btn)
        private val questionRow: LinearLayout = view.findViewById(R.id.question_row)
        private val questionText: TextView = view.findViewById(R.id.question_text)
        private val questionOptions: LinearLayout = view.findViewById(R.id.question_options)
        private val questionInput: EditText = view.findViewById(R.id.question_input)
        private val questionSend: Button = view.findViewById(R.id.question_send)

        private var part: Part.Tool? = null

        fun bind(p: Part.Tool, key: String) {
            part = p
            title.text = buildString {
                append(p.title.ifEmpty { p.tool })
            }
            chevron.visibility = View.VISIBLE
            val open = expanded.contains(key)
            chevron.rotation = if (open) 0f else 180f

            when (p.state) {
                "running" -> {
                    spinner.visibility = View.VISIBLE
                    icon.visibility = View.GONE
                    stateText.text = ctx.getString(R.string.tool_running)
                }
                "completed" -> {
                    spinner.visibility = View.GONE
                    icon.visibility = View.VISIBLE
                    icon.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_check))
                    icon.drawable.setTint(ContextCompat.getColor(ctx, R.color.success))
                    stateText.text = ctx.getString(R.string.tool_done)
                }
                "error" -> {
                    spinner.visibility = View.GONE
                    icon.visibility = View.VISIBLE
                    icon.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_stop))
                    icon.drawable.setTint(ContextCompat.getColor(ctx, R.color.error))
                    stateText.text = ctx.getString(R.string.tool_error)
                }
                else -> {
                    spinner.visibility = View.GONE
                    icon.visibility = View.VISIBLE
                    icon.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_clock))
                    icon.drawable?.setTint(ContextCompat.getColor(ctx, R.color.warning))
                    stateText.text = ctx.getString(R.string.tool_waiting)
                }
            }

            val detailParts = mutableListOf<String>()
            p.input?.let { detailParts.add(it.toString(2)) }
            p.output?.let { detailParts.add(it) }
            detail.text = detailParts.joinToString("\n---\n")
            detail.visibility = if (open && detailParts.isNotEmpty()) View.VISIBLE else View.GONE
            chevron.visibility = if (detailParts.isNotEmpty()) View.VISIBLE else View.GONE

            root.setOnClickListener {
                if (expanded.contains(key)) expanded.remove(key) else expanded.add(key)
                notifyItemChanged(bindingAdapterPosition)
            }

            // approval
            if (p.approval != null) {
                approvalRow.visibility = View.VISIBLE
                approvalCommand.text = p.approval!!.command
                approveBtn.setOnClickListener { listener.onApprove(p) }
                rejectBtn.setOnClickListener { listener.onReject(p) }
            } else {
                approvalRow.visibility = View.GONE
            }

            // question
            if (p.question != null) {
                questionRow.visibility = View.VISIBLE
                questionText.text = p.question!!.question
                questionOptions.removeAllViews()
                p.question!!.options?.forEach { opt ->
                    val btn = Button(ctx)
                    btn.text = opt
                    btn.setBackgroundColor(ContextCompat.getColor(ctx, R.color.panel3))
                    btn.setTextColor(ContextCompat.getColor(ctx, R.color.text))
                    btn.textSize = 13f
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = 8
                    btn.layoutParams = lp
                    btn.setOnClickListener { listener.onAnswer(p, opt) }
                    questionOptions.addView(btn)
                }
                questionInput.visibility = if (p.question!!.options.isNullOrEmpty()) View.VISIBLE else View.GONE
                questionSend.visibility = questionInput.visibility
                questionSend.setOnClickListener {
                    val a = questionInput.text.toString().trim()
                    if (a.isNotEmpty()) listener.onAnswer(p, a)
                }
            } else {
                questionRow.visibility = View.GONE
            }
        }
    }
}
