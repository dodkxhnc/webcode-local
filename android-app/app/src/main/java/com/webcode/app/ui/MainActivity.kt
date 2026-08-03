package com.webcode.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.webcode.app.R
import com.webcode.app.api.Part
import com.webcode.app.api.SessionMessage
import com.webcode.app.api.SessionMeta
import com.webcode.app.api.parseApproval
import com.webcode.app.api.parseQuestion
import com.webcode.app.local.ChatEngine
import com.webcode.app.local.EngineListener
import com.webcode.app.local.Engines
import org.json.JSONObject

class MainActivity : AppCompatActivity(), ChatListener {

    private lateinit var drawer: DrawerLayout
    private lateinit var chatList: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var inputBox: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var stopBtn: ImageButton
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var emptyView: View
    private lateinit var navWorkspace: TextView
    private lateinit var navUsage: TextView
    private lateinit var sessionList: RecyclerView

    private val engine: ChatEngine get() = Engines.current(this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = mutableListOf<SessionMeta>()
    private val messages = mutableListOf<SessionMessage>()
    private var activeId: String? = null
    private var streaming = false

    private var localUserId: String? = null
    private var localAssistantId: String? = null
    private var lastMsgId: String? = null
    private var pendingDelta = StringBuilder()
    private var pendingThinking = StringBuilder()
    private var flushScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawer = findViewById(R.id.drawer_layout)
        chatList = findViewById(R.id.chat_list)
        inputBox = findViewById(R.id.input_box)
        sendBtn = findViewById(R.id.send_btn)
        stopBtn = findViewById(R.id.stop_btn)
        titleText = findViewById(R.id.title_text)
        subtitleText = findViewById(R.id.subtitle_text)
        emptyView = findViewById(R.id.empty_view)
        navWorkspace = findViewById(R.id.nav_workspace)
        navUsage = findViewById(R.id.nav_usage)
        sessionList = findViewById(R.id.session_list)

        adapter = ChatAdapter(this, this, messages)
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = adapter

        findViewById<View>(R.id.menu_btn).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<View>(R.id.settings_btn).setOnClickListener { newChat() }
        sendBtn.setOnClickListener { sendMessage() }
        stopBtn.setOnClickListener { stopAgent() }

        findViewById<View>(R.id.drawer_settings).setOnClickListener {
            drawer.closeDrawer(GravityCompat.START)
            openSettings()
        }

        findViewById<View>(R.id.suggestion_1).setOnClickListener { useSuggestion(0) }
        findViewById<View>(R.id.suggestion_2).setOnClickListener { useSuggestion(1) }
        findViewById<View>(R.id.suggestion_3).setOnClickListener { useSuggestion(2) }

        if (!com.webcode.app.local.LocalEngine.isConfigured(this)) {
            Toast.makeText(this, "尚未配置 API Key，请在设置页填写", Toast.LENGTH_LONG).show()
        }

        loadSessions()
        loadInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        loadInfo()
    }

    override fun onStart() {
        super.onStart()
        // API 24-28 需要存储权限写公共目录崩溃日志
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                100
            )
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, LocalSetupActivity::class.java))
    }

    private fun useSuggestion(i: Int) {
        val texts = listOf(
            getString(R.string.suggest_1),
            getString(R.string.suggest_2),
            getString(R.string.suggest_3)
        )
        inputBox.setText(texts[i])
        sendMessage()
    }

    private fun loadInfo() {
        Thread {
            try {
            val w = engine.workspaceInfo()
            val u = engine.usage(activeId)
            runOnUiThread {
                w?.let {
                    navWorkspace.text = "工作目录：${it.workspace}"
                    subtitleText.text = if (it.mock) "mock 模型" else "模型：${it.model}"
                }
                u?.let {
                    navUsage.text = getString(
                        R.string.usage_total,
                        formatNum(it.totalTokens),
                        it.requestCount.toString()
                    )
                }
            }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun formatNum(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 10_000 -> String.format(java.util.Locale.US, "%.1f万", n / 10_000.0)
        else -> n.toString()
    }

    private fun loadSessions() {
        Thread {
            try {
                val list = engine.listSessions()
                runOnUiThread {
                    sessions.clear()
                    sessions.addAll(list)
                    renderNavSessions()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "加载会话失败：${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun renderNavSessions() {
        if (sessionList.adapter == null) {
            sessionList.layoutManager = LinearLayoutManager(this)
            sessionList.adapter = SessionListAdapter(sessions) { id -> selectSession(id) }
        } else {
            (sessionList.adapter as SessionListAdapter).update(sessions)
        }
    }

    private fun selectSession(id: String) {
        if (id == activeId) return
        drawer.closeDrawer(GravityCompat.START)
        engine.cancel(activeId)
        streaming = false
        stopBtn.visibility = View.GONE
        sendBtn.visibility = View.VISIBLE
        Thread {
            try {
                val s = engine.getSession(id)
                runOnUiThread {
                    if (s == null) {
                        Toast.makeText(this, "会话不存在", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    activeId = s.id
                    messages.clear()
                    messages.addAll(s.messages)
                    adapter.submit()
                    titleText.text = s.title
                    emptyView.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                    loadInfo()
                        scrollToBottom()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "加载会话失败", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun newChat() {
        if (streaming) return
        drawer.closeDrawer(GravityCompat.START)
        engine.cancel(activeId)
        activeId = null
        messages.clear()
        adapter.clear()
        adapter.statusText = null
        emptyView.visibility = View.VISIBLE
        titleText.text = getString(R.string.new_chat)
        scrollToBottom()
    }

    private fun sendMessage() {
        val content = inputBox.text.toString().trim()
        if (content.isEmpty() || streaming) return
        inputBox.setText("")

        localUserId = "local_user_${System.currentTimeMillis()}"
        localAssistantId = "local_assistant_${System.currentTimeMillis()}"
        lastMsgId = localAssistantId
        pendingDelta.clear()
        pendingThinking.clear()

        val userMsg = SessionMessage(localUserId!!, "user", mutableListOf(Part.Text(content)), System.currentTimeMillis())
        val assistantMsg = SessionMessage(localAssistantId!!, "assistant", mutableListOf(), System.currentTimeMillis())
        messages.add(userMsg)
        messages.add(assistantMsg)
        adapter.submit()
        emptyView.visibility = View.GONE
        adapter.setStatus("思考中…")
        setStreaming(true)
        scrollToBottom()

        engine.start(activeId, content, engineListener)
    }

    private fun stopAgent() {
        engine.cancel(activeId)
    }

    private fun setStreaming(v: Boolean) {
        streaming = v
        sendBtn.visibility = if (v) View.GONE else View.VISIBLE
        stopBtn.visibility = if (v) View.VISIBLE else View.GONE
    }

    private val engineListener = object : EngineListener {
        override fun onEvent(type: String, data: JSONObject) {
            // 引擎事件来自后台线程（OkHttp / 本地循环），必须切回主线程再操作 UI
            mainHandler.post { handleEvent(type, data) }
        }

        override fun onStreamError(message: String) {
            mainHandler.post {
                if (streaming) {
                    adapter.setStatus("连接中断：$message")
                }
            }
        }
    }

    private fun handleEvent(type: String, data: JSONObject) {
        try {
            handleEventInner(type, data)
        } catch (e: Throwable) {
            logInternalError("事件 $type 处理失败", e)
            adapter.setStatus("内部错误：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun logInternalError(what: String, e: Throwable) {
        try {
            val sw = java.io.StringWriter()
            java.io.PrintWriter(sw).use { e.printStackTrace(it) }
            val f = java.io.File(filesDir, "engine.log")
            f.appendText("[$what] ${System.currentTimeMillis()}\n${sw}\n\n")
        } catch (ex: Exception) {
        }
    }

    private fun handleEventInner(type: String, data: JSONObject) {
        when (type) {
            "session" -> {
                val sid = data.optString("sessionId")
                if (sid.isNotEmpty()) {
                    activeId = sid
                    refreshSessionList()
                }
            }
            "user_message" -> {
                val serverUserId = data.optJSONObject("message")?.optString("id")
                val serverAssistantId = data.optString("assistantMessageId")
                if (!serverUserId.isNullOrEmpty() && serverAssistantId.isNotEmpty()) {
                    localUserId?.let { lu ->
                        localAssistantId?.let { la ->
                            adapter.replaceIds(lu, serverUserId, la, serverAssistantId)
                        }
                    }
                    localUserId = null
                    localAssistantId = null
                }
            }
            "delta" -> {
                val text = data.optString("text")
                val mid = data.optString("messageId")
                if (mid.isNotEmpty()) {
                    lastMsgId = mid
                    pendingDelta.append(text)
                    scheduleFlush()
                }
            }
            "reasoning_delta" -> {
                val text = data.optString("text")
                val mid = data.optString("messageId")
                if (mid.isNotEmpty()) {
                    lastMsgId = mid
                    pendingThinking.append(text)
                    scheduleFlush()
                }
            }
            "tool_start" -> {
                flushNow()
                val mid = data.optString("messageId")
                val tool = data.optString("tool")
                val part = Part.Tool(
                    id = data.optString("partId"),
                    tool = tool,
                    title = data.optString("title"),
                    state = "running",
                    input = data.optJSONObject("input"),
                    output = null,
                    approval = null,
                    question = null
                )
                adapter.findMessage(mid)?.let { m ->
                    m.parts.add(part)
                    adapter.submit()
                    scrollToBottom()
                }
            }
            "tool_output", "tool_error" -> {
                flushNow()
                val mid = data.optString("messageId")
                val partId = data.optString("partId")
                val output = if (type == "tool_error") data.optString("error") else data.optString("output")
                adapter.findMessage(mid)?.let { m ->
                    val p = m.parts.filterIsInstance<Part.Tool>().find { it.id == partId }
                    if (p != null) {
                        p.state = if (type == "tool_error") "error" else "completed"
                        p.output = output
                        p.approval = null
                        p.question = null
                        adapter.submit()
                        scrollToBottom()
                    }
                }
            }
            "approval_required" -> {
                flushNow()
                val mid = data.optString("messageId")
                val partId = data.optString("partId")
                val approval = data.optJSONObject("approval")
                approval?.let { notifyInteraction("AI 请求执行命令", it.optString("command").take(60)) }
                adapter.findMessage(mid)?.let { m ->
                    val p = m.parts.filterIsInstance<Part.Tool>().find { it.id == partId }
                    if (p != null && approval != null) {
                        p.state = "requires_action"
                        p.approval = parseApproval(approval)
                        adapter.submit()
                        scrollToBottom()
                    }
                }
            }
            "question_required" -> {
                flushNow()
                val mid = data.optString("messageId")
                val partId = data.optString("partId")
                val question = data.optJSONObject("question")
                question?.let { notifyInteraction("AI 向你提问", it.optString("question").take(80)) }
                adapter.findMessage(mid)?.let { m ->
                    val p = m.parts.filterIsInstance<Part.Tool>().find { it.id == partId }
                    if (p != null && question != null) {
                        p.state = "requires_action"
                        p.question = parseQuestion(question)
                        adapter.submit()
                        scrollToBottom()
                    }
                }
            }
            "status" -> {
                val s = data.optString("status")
                adapter.setStatus(if (s.isEmpty()) null else s)
            }
            "done" -> {
                flushNow()
                adapter.setStatus(null)
                setStreaming(false)
                loadInfo()
                refreshSessionList()
                scrollToBottom()
            }
            "aborted" -> {
                flushNow()
                adapter.setStatus(null)
                setStreaming(false)
                loadInfo()
                refreshSessionList()
            }
            "error" -> {
                flushNow()
                adapter.setStatus("错误：${data.optString("message")}")
                setStreaming(false)
            }
        }
    }

    private fun notifyInteraction(title: String, text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        "webcode_action", "AI 交互请求", NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(this, "webcode_action")
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(1001, n)
        } catch (e: Exception) {
        }
    }

    private fun refreshSessionList() {
        Thread {
            try {
                val list = engine.listSessions()
                runOnUiThread {
                    sessions.clear()
                    sessions.addAll(list)
                    renderNavSessions()
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        mainHandler.postDelayed({ flushScheduled = false; flushNow() }, 100)
    }

    private fun flushNow() {
        if (pendingDelta.isNotEmpty()) {
            val text = pendingDelta.toString()
            pendingDelta.clear()
            lastMsgId?.let { adapter.appendDelta(it, text) }
        }
        if (pendingThinking.isNotEmpty()) {
            val text = pendingThinking.toString()
            pendingThinking.clear()
            lastMsgId?.let { adapter.appendThinkingDelta(it, text) }
        }
        scrollToBottom()
    }

    private fun scrollToBottom() {
        chatList.post {
            if (adapter.itemCount > 0) {
                chatList.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    // ChatListener
    override fun onApprove(part: Part.Tool) {
        part.approval?.let { req ->
            engine.approve(req.requestId, true)
            part.state = "running"
            part.approval = null
            adapter.submit()
        }
    }

    override fun onReject(part: Part.Tool) {
        part.approval?.let { req ->
            engine.approve(req.requestId, false)
            part.state = "completed"
            part.output = "用户拒绝执行该操作"
            part.approval = null
            adapter.submit()
        }
    }

    override fun onAnswer(part: Part.Tool, answer: String) {
        part.question?.let { q ->
            engine.answer(q.questionId, answer)
            part.state = "completed"
            part.output = "用户回答：$answer"
            part.question = null
            adapter.submit()
        }
    }

    class SessionListAdapter(
        private var items: List<SessionMeta>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<SessionListAdapter.VH>() {

        fun update(list: List<SessionMeta>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.session_title)
            val meta: TextView = view.findViewById(R.id.session_meta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.title.text = s.title
            val usage = s.usage
            val tokens = usage?.optLong("promptTokens")?.plus(usage.optLong("completionTokens")) ?: 0
            holder.meta.text = "${s.id.take(8)} · $tokens tokens"
            holder.itemView.setOnClickListener { onClick(s.id) }
            holder.itemView.setOnLongClickListener {
                showMenu(holder.itemView.context, s)
                true
            }
        }

        private fun showMenu(ctx: android.content.Context, meta: SessionMeta) {
            val options = arrayOf("重命名", "删除")
            AlertDialog.Builder(ctx)
                .setTitle(meta.title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> rename(ctx, meta)
                        1 -> delete(ctx, meta)
                    }
                }
                .show()
        }

        private fun rename(ctx: android.content.Context, meta: SessionMeta) {
            val input = EditText(ctx)
            input.setText(meta.title)
            input.setSingleLine(true)
            AlertDialog.Builder(ctx)
                .setTitle("重命名会话")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val title = input.text.toString().trim()
                    if (title.isNotEmpty()) {
                        Thread {
                            try {
                            Engines.current(ctx).renameSession(meta.id, title)
                            meta.title = title
                            android.os.Handler(android.os.Looper.getMainLooper())
                                .post { notifyDataSetChanged() }
                            } catch (e: Exception) {
                            }
                        }.start()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        private fun delete(ctx: android.content.Context, meta: SessionMeta) {
            AlertDialog.Builder(ctx)
                .setMessage("确定删除会话「${meta.title}」？")
                .setPositiveButton("删除") { _, _ ->
                    Thread {
                        try {
                            Engines.current(ctx).deleteSession(meta.id)
                            android.os.Handler(android.os.Looper.getMainLooper())
                                .post {
                                    items = items.filterNot { it.id == meta.id }
                                    notifyDataSetChanged()
                                }
                        } catch (e: Exception) {
                        }
                    }.start()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}
