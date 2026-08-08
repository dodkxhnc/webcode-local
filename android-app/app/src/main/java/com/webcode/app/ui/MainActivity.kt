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
import com.webcode.app.BuildConfig
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
    private val pendingOps = mutableListOf<Pair<String, String>>()
    private var flushScheduled = false
    private var stickToBottom = true
    private var multiSelect = false
    private val selectedSessions = mutableSetOf<String>()

    // 小窗等后台服务修改会话后广播刷新（overlay 不触发 onResume）
    private val sessionChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            refreshSessionList()
        }
    }

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
        // 贴底跟随：用户上滑阅读历史时不强制拉到最底部，回到底部附近后恢复跟随
        chatList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val total = lm.itemCount
                if (total > 0) {
                    stickToBottom = lm.findLastVisibleItemPosition() >= total - 2
                }
            }
        })

        setupQuickThinkingControls()
        findViewById<View>(R.id.menu_btn).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<View>(R.id.settings_btn).setOnClickListener { newChat() }
        sendBtn.setOnClickListener { sendMessage() }
        stopBtn.setOnClickListener { stopAgent() }

        findViewById<View>(R.id.drawer_settings).setOnClickListener {
            drawer.closeDrawer(GravityCompat.START)
            openSettings()
        }
        findViewById<View>(R.id.drawer_multiselect).setOnClickListener {
            if (multiSelect) {
                // 多选模式下点按钮 = 删除选中
                confirmDeleteSelected()
            } else {
                multiSelect = true
                updateMultiSelectUi()
                renderNavSessions()
            }
        }
        findViewById<View>(R.id.drawer_multi_done).setOnClickListener {
            multiSelect = false
            selectedSessions.clear()
            updateMultiSelectUi()
            renderNavSessions()
        }
        findViewById<View>(R.id.drawer_partitions).setOnClickListener {
            showPartitionManager()
        }

        if (!com.webcode.app.local.LocalEngine.isConfigured(this)) {
            Toast.makeText(this, "尚未配置 API Key，请在设置页填写", Toast.LENGTH_LONG).show()
        }

        loadSessions()
        loadInfo()
        // 恢复上次会话：退出重进后直接回到原对话（持久任务友好），没有则保持新对话页
        val last = com.webcode.app.local.LocalEngine.lastSession(this)
        if (!last.isNullOrEmpty()) {
            selectSession(last)
        }
        // 小窗服务会话变更广播
        try {
            registerReceiver(
                sessionChangedReceiver,
                android.content.IntentFilter("webcode.sessions_changed"),
                android.content.Context.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
        }
        // 版本更新检查（后台拉取仓库 version.json）
        checkForUpdate()
    }

    private fun checkForUpdate() {
        com.webcode.app.local.UpdateChecker.checkLatest { info ->
            runOnUiThread {
                try {
                    if (info == null || info.versionCode <= BuildConfig.VERSION_CODE) return@runOnUiThread
                    android.app.AlertDialog.Builder(this)
                        .setTitle("发现新版本 ${info.versionName}")
                        .setMessage(info.notes.ifEmpty { "有新版本可用，是否下载更新？" })
                        .setPositiveButton("下载更新") { _, _ -> downloadUpdate(info.apkUrl) }
                        .setNegativeButton("忽略", null)
                        .show()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun downloadUpdate(url: String) {
        if (url.isBlank()) {
            android.widget.Toast.makeText(this, "缺少下载地址", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        com.webcode.app.local.UpdateChecker.downloadWithDialog(this, url)
    }

    override fun onStop() {
        super.onStop()
        // 离开界面立即落盘，防止模型输出丢失
        engine.onLeave(activeId)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(sessionChangedReceiver)
        } catch (e: Exception) {
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        loadInfo()
        // 小窗可能新建/修改了会话，回来立即刷新列表
        refreshSessionList()
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

    private fun setupQuickThinkingControls() {
        val mode = findViewById<android.widget.Spinner>(R.id.quick_mode)
        val effort = findViewById<android.widget.Spinner>(R.id.quick_effort)
        val modeLabels = listOf("自动", "思考", "非思考")
        val modeValues = listOf("auto", "auto", "none")
        val effortLabels = listOf("低", "中", "高", "最高")
        val effortValues = listOf("low", "medium", "high", "max")
        mode.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, modeLabels
        )
        effort.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, effortLabels
        )
        val saved = com.webcode.app.local.LocalEngine.reasoningSetting(this)
        mode.setSelection(
            when (saved) {
                "none" -> 2
                "low", "medium", "high" -> 1
                else -> 0
            }
        )
        effort.setSelection(effortValues.indexOf(saved).coerceAtLeast(0))

        fun apply() {
            val reasoning = when (mode.selectedItemPosition) {
                2 -> "none"
                1 -> effortValues[effort.selectedItemPosition]
                else -> "auto"
            }
            val (key, baseUrl, model) = com.webcode.app.local.LocalEngine.loadConfig(this)
            com.webcode.app.local.LocalEngine.saveConfig(this, key, baseUrl, model, reasoning)
        }
        mode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        effort.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun openSettings() {
        startActivity(
            Intent(this, LocalSetupActivity::class.java)
                .putExtra("skip_auto_enter", true)
        )
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

    private fun updateMultiSelectUi() {
        val btn = findViewById<TextView>(R.id.drawer_multiselect)
        val done = findViewById<TextView>(R.id.drawer_multi_done)
        if (multiSelect) {
            btn.text = "删除选中(${selectedSessions.size})"
            btn.setTextColor(getColor(R.color.error))
            done.visibility = View.VISIBLE
        } else {
            btn.text = "多选"
            btn.setTextColor(getColor(R.color.accent))
            done.visibility = View.GONE
        }
    }

    private fun renderNavSessions() {
        val rows = buildList {
            val defaultSessions = sessions.filter { it.partition.isNullOrEmpty() }
            val byPartition = sessions.filter { !it.partition.isNullOrEmpty() }
                .groupBy { it.partition!! }
            add(SessionListAdapter.HEADER to null as SessionMeta?)
            addAll(defaultSessions.map { SessionListAdapter.ITEM to it })
            for ((name, list) in byPartition.toSortedMap()) {
                add(SessionListAdapter.HEADER to null as SessionMeta?)
                addAll(list.map { SessionListAdapter.ITEM to it })
            }
        }
        if (sessionList.adapter == null) {
            sessionList.layoutManager = LinearLayoutManager(this)
            sessionList.adapter = SessionListAdapter(rows) { id -> selectSession(id) }.apply {
                onLongPress = { meta -> sessionMenu(meta) }
                onSelectionChanged = { n -> updateMultiSelectUi() }
            }
        } else {
            val a = sessionList.adapter as SessionListAdapter
            a.update(rows)
            a.multiSelect = multiSelect
            a.selected = selectedSessions
            a.onLongPress = { meta -> sessionMenu(meta) }
            a.onSelectionChanged = { n -> updateMultiSelectUi() }
        }
    }

    private fun sessionMenu(meta: SessionMeta) {
        val store = com.webcode.app.local.LocalStore(this)
        val options = mutableListOf("重命名", "删除", "移动到分区", "移出分区（回默认）")
        AlertDialog.Builder(this)
            .setTitle(meta.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this)
                        input.setText(meta.title)
                        AlertDialog.Builder(this)
                            .setTitle("重命名")
                            .setView(input)
                            .setPositiveButton("确定") { _, _ ->
                                val t = input.text.toString().trim()
                                if (t.isNotEmpty()) {
                                    Thread { engine.renameSession(meta.id, t) }.start()
                                    refreshSessionList()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    1 -> {
                        AlertDialog.Builder(this)
                            .setMessage("删除会话「${meta.title}」？")
                            .setPositiveButton("删除") { _, _ ->
                                Thread {
                                    engine.deleteSession(meta.id)
                                    runOnUiThread {
                                        refreshSessionList()
                                        if (activeId == meta.id) newChat()
                                    }
                                }.start()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    2 -> {
                        val partitions = store.partitions()
                        val opts = partitions.toMutableList().apply { add("＋ 新建分区") }
                        AlertDialog.Builder(this)
                            .setTitle("移动到分区")
                            .setItems(opts.toTypedArray()) { _, w ->
                                if (w < partitions.size) {
                                    store.setPartition(meta.id, partitions[w])
                                    refreshSessionList()
                                } else {
                                    val input = EditText(this)
                                    input.hint = "分区名称"
                                    AlertDialog.Builder(this)
                                        .setTitle("新建分区")
                                        .setView(input)
                                        .setPositiveButton("确定") { _, _ ->
                                            val name = input.text.toString().trim()
                                            if (name.isNotEmpty()) {
                                                store.setPartition(meta.id, name)
                                                refreshSessionList()
                                            }
                                        }
                                        .setNegativeButton("取消", null)
                                        .show()
                                }
                            }
                            .show()
                    }
                    3 -> {
                        store.setPartition(meta.id, null)
                        refreshSessionList()
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteSelected() {
        val n = selectedSessions.size
        AlertDialog.Builder(this)
            .setMessage("删除选中的 $n 个会话？（可跨分区）")
            .setPositiveButton("删除") { _, _ ->
                Thread {
                    for (id in selectedSessions) {
                        try {
                            engine.deleteSession(id)
                        } catch (e: Exception) {
                        }
                    }
                    runOnUiThread {
                        selectedSessions.clear()
                        multiSelect = false
                        updateMultiSelectUi()
                        refreshSessionList()
                    }
                }.start()
            }
            .setNegativeButton("取消") { _, _ ->
                selectedSessions.clear()
                updateMultiSelectUi()
                renderNavSessions()
            }
            .show()
    }

    private fun showPartitionManager() {
        val store = com.webcode.app.local.LocalStore(this)
        val partitions = store.partitions()
        if (partitions.isEmpty()) {
            Toast.makeText(this, "暂无分区", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = partitions.map { "$it（${store.list().count { s -> s.partition == it }} 会话）" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("分区管理（${partitions.size}）")
            .setItems(labels) { _, which ->
                val name = partitions[which]
                val count = store.list().count { it.partition == name }
                AlertDialog.Builder(this)
                    .setTitle("分区「$name」")
                    .setItems(arrayOf("释放分区（$count 个会话移回默认）", "删除分区（连同 $count 个会话）")) { _, w ->
                        when (w) {
                            0 -> {
                                store.releasePartition(name)
                                refreshSessionList()
                            }
                            1 -> {
                                store.deletePartition(name)
                                refreshSessionList()
                            }
                        }
                    }
                    .show()
            }
            .setPositiveButton("关闭", null)
            .show()
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
                        // 复位无效会话 id，避免后续发消息反复创建新会话
                        activeId = null
                        return@runOnUiThread
                    }
                    activeId = s.id
                    com.webcode.app.local.LocalEngine.setLastSession(this, s.id)
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
        pendingOps.clear()

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
            com.webcode.app.termux.DiagLog.log(this@MainActivity, "Net", "流错误: $message")
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
        com.webcode.app.termux.DiagLog.log(this, "UI", "$what: ${e.message ?: e.javaClass.simpleName}")
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
                    com.webcode.app.local.LocalEngine.setLastSession(this, sid)
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
                    lastMsgId = serverAssistantId
                    localUserId = null
                    localAssistantId = null
                }
            }
            "delta" -> {
                val text = data.optString("text")
                val mid = data.optString("messageId")
                if (mid.isNotEmpty()) {
                    lastMsgId = mid
                    pendingOps.add("delta" to text)
                    scheduleFlush()
                }
            }
            "reasoning_delta" -> {
                val text = data.optString("text")
                val mid = data.optString("messageId")
                if (mid.isNotEmpty()) {
                    lastMsgId = mid
                    pendingOps.add("thinking" to text)
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
        if (pendingOps.isNotEmpty()) {
            val ops = pendingOps.toList()
            pendingOps.clear()
            for ((kind, text) in ops) {
                lastMsgId?.let { mid ->
                    if (kind == "thinking") {
                        adapter.appendThinkingDelta(mid, text)
                    } else {
                        adapter.appendDelta(mid, text)
                    }
                }
            }
        }
        scrollToBottom()
    }

    private fun scrollToBottom() {
        if (!stickToBottom) return
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
        private var rows: List<Pair<Int, SessionMeta?>>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<SessionListAdapter.VH>() {

        companion object {
            const val HEADER = 0
            const val ITEM = 1
        }

        var multiSelect = false
        var selected: MutableSet<String> = mutableSetOf()
        var onLongPress: ((SessionMeta) -> Unit)? = null
        var onSelectionChanged: ((Int) -> Unit)? = null

        fun update(list: List<Pair<Int, SessionMeta?>>) {
            rows = list
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.session_title)
            val meta: TextView = view.findViewById(R.id.session_meta)
            val check: android.widget.CheckBox = view.findViewById(R.id.session_check)
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = rows[position].first

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (kind, s) = rows[position]
            if (kind == HEADER) {
                holder.title.text = s?.partition ?: "默认分区"
                holder.title.setTextColor(holder.itemView.context.getColor(R.color.accent))
                holder.title.textSize = 12f
                holder.meta.text = ""
                holder.check.visibility = View.GONE
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                return
            }
            val meta = s ?: return
            holder.title.text = meta.title
            holder.title.setTextColor(holder.itemView.context.getColor(R.color.text))
            holder.title.textSize = 14f
            val usage = meta.usage
            val tokens = usage?.optLong("promptTokens")?.plus(usage.optLong("completionTokens")) ?: 0
            if (multiSelect) {
                holder.check.visibility = View.VISIBLE
                holder.check.isChecked = selected.contains(meta.id)
                holder.meta.text = "${meta.id.take(8)} · $tokens tokens"
                val toggle = {
                    if (selected.contains(meta.id)) selected.remove(meta.id) else selected.add(meta.id)
                    notifyDataSetChanged()
                    onSelectionChanged?.invoke(selected.size)
                }
                holder.check.setOnClickListener { toggle() }
                holder.itemView.setOnClickListener { toggle() }
                holder.itemView.setOnLongClickListener(null)
            } else {
                holder.check.visibility = View.GONE
                holder.meta.text = "${meta.id.take(8)} · $tokens tokens"
                holder.itemView.setOnClickListener { onClick(meta.id) }
                holder.itemView.setOnLongClickListener {
                    onLongPress?.invoke(meta)
                    true
                }
            }
        }
    }
}
