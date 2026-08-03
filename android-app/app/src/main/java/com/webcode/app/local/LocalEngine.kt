package com.webcode.app.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.webcode.app.api.Part
import com.webcode.app.api.Session
import com.webcode.app.api.SessionMessage
import com.webcode.app.api.SessionMeta
import com.webcode.app.api.SettingsInfo
import com.webcode.app.api.Usage
import com.webcode.app.api.WorkspaceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地直连模式：App 直接调用 DeepSeek Responses API，
 * 在手机上执行工具循环（文件/设备信息/搜索/提问），全部数据在本机。
 */
class LocalEngine(context: Context) : ChatEngine {

    override val isLocal = true

    private val appContext = context.applicationContext
    private val store = LocalStore(appContext)

    init {
        com.webcode.app.termux.TermuxRuntime.init(appContext)
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val pendingQuestions = ConcurrentHashMap<String, (String) -> Unit>()
    private val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val abort = AtomicBoolean(false)
    private var running = false
    private var currentSessionId: String? = null

    // 本轮 UI 状态
    private var currentAssistantId = ""
    private var currentListener: EngineListener? = null
    private var currentMessages: MutableList<SessionMessage> = mutableListOf()

    private var lastUserContent = ""

    private val workspaceDir: File
        get() = File(appContext.filesDir, "workspace").apply { mkdirs() }

    private fun apiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
    private fun baseUrl(): String = prefs.getString(KEY_BASE_URL, DirectClient.DEFAULT_BASE_URL) ?: DirectClient.DEFAULT_BASE_URL
    private fun model(): String = prefs.getString(KEY_MODEL, DirectClient.DEFAULT_MODEL) ?: DirectClient.DEFAULT_MODEL
    private fun reasoningEffort(): String = prefs.getString(KEY_REASONING, "auto") ?: "auto"

    /* ============ 会话 CRUD ============ */
    override fun listSessions(): List<SessionMeta> = store.list()
    override fun createSession(title: String?): SessionMeta = store.create(title)
    override fun getSession(id: String): Session? = store.get(id)
    override fun renameSession(id: String, title: String): Boolean {
        store.rename(id, title)
        return true
    }
    override fun deleteSession(id: String) = store.delete(id)
    override fun usage(sessionId: String?): Usage? {
        val s = sessionId?.let { store.get(it) }
        val u = s?.usage
        return Usage(
            totalTokens = (u?.optLong("promptTokens") ?: 0) + (u?.optLong("completionTokens") ?: 0),
            promptTokens = u?.optLong("promptTokens") ?: 0,
            completionTokens = u?.optLong("completionTokens") ?: 0,
            requestCount = 0,
            sessionUsage = u
        )
    }
    override fun workspaceInfo(): WorkspaceInfo =
        WorkspaceInfo(
            workspace = workspaceDir.absolutePath,
            model = model(),
            mock = false,
            hasApiKey = apiKey().isNotEmpty()
        )
    override fun settingsInfo(): SettingsInfo {
        val (key, base, m) = loadConfig(appContext)
        return SettingsInfo(
            provider = "deepseek",
            baseUrl = base,
            model = m,
            authType = "bearer",
            apiKeyHeader = "",
            thinking = null,
            reasoningEffort = null,
            maxSteps = null,
            hasApiKey = key.isNotEmpty(),
            apiKeySet = key.isNotEmpty(),
            envOverridden = false
        )
    }
    override fun saveSettings(o: JSONObject): String? {
        var key = apiKey()
        if (o.has("apiKey")) key = o.optString("apiKey")
        saveConfig(appContext, key, o.optString("baseUrl", baseUrl()), o.optString("model", model()))
        return null
    }

    /* ============ 工具循环 ============ */
    override fun start(sessionId: String?, content: String, listener: EngineListener) {
        if (running) return
        running = true
        abort.set(false)
        currentListener = listener
        lastUserContent = content

        // 会话
        val sid = if (sessionId != null && store.get(sessionId) != null) sessionId else {
            store.create(content.take(30)).id
        }
        currentSessionId = sid
        emit("session", JSONObject().put("sessionId", sid))

        // 载入历史（UI 消息）
        val existing = store.get(sid)
        currentMessages = (existing?.messages ?: emptyList()).toMutableList()
        val items = store.items(sid)

        // 用户消息（本地 + 服务端统一）
        val userMsg = SessionMessage(
            id = "m_" + UUID.randomUUID().toString().substring(0, 13),
            role = "user",
            parts = mutableListOf(Part.Text(content)),
            createdAt = System.currentTimeMillis()
        )
        val assistantMsg = SessionMessage(
            id = "m_" + UUID.randomUUID().toString().substring(0, 13),
            role = "assistant",
            parts = mutableListOf(),
            createdAt = System.currentTimeMillis()
        )
        currentAssistantId = assistantMsg.id
        currentMessages.add(userMsg)
        currentMessages.add(assistantMsg)
        emit("user_message", JSONObject()
            .put("message", JSONObject().put("id", userMsg.id))
            .put("assistantMessageId", assistantMsg.id))

        Thread {
            try {
                runLoop(sid, items)
            } catch (e: Exception) {
                emit("error", JSONObject().put("message", e.message ?: "未知错误"))
            } finally {
                running = false
                currentListener = null
            }
        }.start()
    }

    private fun runLoop(sid: String, initialItems: JSONArray) {
        try {
            runLoopInner(sid, initialItems)
        } catch (e: Throwable) {
            try {
                emit("error", JSONObject().put("message", "本地引擎错误: ${e.message ?: e.javaClass.simpleName}"))
                storeMessages(sid)
            } catch (ex: Exception) {
            }
        }
    }

    private fun runLoopInner(sid: String, initialItems: JSONArray) {
        val client = DirectClient(apiKey(), baseUrl(), model())
        // 清洗本地记忆：丢弃悬空的 function_call（上次中断留下的），空输出补占位，避免 400
        val items = sanitizeItems(initialItems)
        var step = 0

        // 本轮用户消息 item
        items.put(DirectClient.messageItem("user", lastUserContent))

        while (step < 16 && !abort.get()) {
            if (abort.get()) break
            step++
            emit("status", JSONObject()
                .put("messageId", currentAssistantId)
                .put("status", if (step == 1) "思考中…" else "第 $step 轮工具循环…"))

            val streamListener = object : DirectClient.Listener {
                override fun onEvent(ev: DirectClient.ResponseEvent) {
                    when (ev.type) {
                        "response.reasoning_text.delta" -> emit("reasoning_delta", JSONObject()
                            .put("messageId", currentAssistantId)
                            .put("text", ev.data.optString("delta")))
                        "response.output_text.delta" -> emit("delta", JSONObject()
                            .put("messageId", currentAssistantId)
                            .put("text", ev.data.optString("delta")))
                        "response.web_search_call.completed" -> {
                            // 服务端已拿到结果，无需客户端处理，仅提示
                            emit("tool_output", JSONObject()
                                .put("messageId", currentAssistantId)
                                .put("partId", "web_search")
                                .put("output", "联网搜索完成（服务端执行）"))
                        }
                    }
                }

                override fun onError(message: String) {
                    emit("error", JSONObject().put("message", message))
                }
            }

            val result = client.create(
                items,
                SYSTEM_PROMPT + "\n工作区路径: ${workspaceDir.absolutePath}",
                TOOLS, streamListener, abort,
                reasoningEffort().takeIf { it != "auto" }
            )

            if (abort.get()) {
                emit("aborted", JSONObject())
                storeMessages(sid)
                return
            }
            if (!result.completed || result.response == null) {
                val err = result.error ?: "请求失败"
                emit("error", JSONObject().put("message", err))
                storeMessages(sid)
                return
            }

            val resp = result.response!!
            addUsage(sid, resp)

            // 解析输出 items（reasoning / web_search_call / function_call 必须按顺序原样回传，否则 400）
            val output = resp.optJSONArray("output") ?: JSONArray()
            val pendingCalls = mutableListOf<Pair<JSONObject, String>>()
            var finalText = StringBuilder()
            var hasWebSearch = false

            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "function_call" -> {
                        val callId = item.optString("call_id").ifEmpty { item.optString("id") }
                        items.put(DirectClient.functionCallItem(callId, item.optString("name"), item.optString("arguments")))
                        pendingCalls.add(item to callId)
                    }
                    "web_search_call" -> {
                        hasWebSearch = true
                        items.put(item) // 原样回传，服务端自动恢复搜索结果
                        emitToolCard("web_search", "web_search", "联网搜索")
                    }
                    "reasoning" -> {
                        items.put(item) // 思考内容必须原样回传
                        val c = item.optJSONArray("content")
                        for (j in 0 until (c?.length() ?: 0)) {
                            c!!.optJSONObject(j)?.let { rc ->
                                emit("reasoning_delta", JSONObject()
                                    .put("messageId", currentAssistantId)
                                    .put("text", rc.optString("text")))
                            }
                        }
                    }
                    "message" -> {
                        val content = item.optJSONArray("content")
                        for (j in 0 until (content?.length() ?: 0)) {
                            val c = content!!.optJSONObject(j)
                            if (c?.optString("type") == "output_text") {
                                finalText.append(c.optString("text"))
                            }
                        }
                        val reasoning = item.optJSONArray("reasoning")
                        for (j in 0 until (reasoning?.length() ?: 0)) {
                            val c = reasoning!!.optJSONObject(j)
                            if (c?.optString("type") == "reasoning_text") {
                                emit("reasoning_delta", JSONObject()
                                    .put("messageId", currentAssistantId)
                                    .put("text", c.optString("text")))
                            }
                        }
                    }
                }
            }

            if (hasWebSearch) {
                emit("tool_output", JSONObject()
                    .put("messageId", currentAssistantId)
                    .put("partId", "web_search")
                    .put("output", "已获取搜索结果"))
            }

            // 执行工具调用
            if (pendingCalls.isEmpty()) {
                if (finalText.isNotEmpty()) {
                    appendPart(Part.Text(finalText.toString()))
                }
                storeItems(sid, items)
                storeMessages(sid)
                emit("status", JSONObject().put("messageId", currentAssistantId).put("status", ""))
                emit("done", JSONObject()
                    .put("messageId", currentAssistantId)
                    .put("finishReason", "stop")
                    .put("sessionId", sid))
                return
            }

            for ((fc, callId) in pendingCalls) {
                if (abort.get()) break
                val name = fc.optString("name")
                val args = fc.optString("arguments")
                emitToolCard(callId, name, "$name ${args.take(60)}")

                val output = executeTool(name, args)
                items.put(DirectClient.functionCallOutputItem(callId, output))
                emit("tool_output", JSONObject()
                    .put("messageId", currentAssistantId)
                    .put("partId", callId)
                    .put("output", output))
                storeMessages(sid)
            }
        }

        if (abort.get()) {
            emit("aborted", JSONObject())
        } else {
            emit("done", JSONObject()
                .put("messageId", currentAssistantId)
                .put("finishReason", "stop")
                .put("sessionId", sid))
        }
        storeItems(sid, items)
        storeMessages(sid)
    }

    /** 清洗会话历史 items：丢弃悬空的 function_call，空输出补占位 */
    private fun sanitizeItems(initial: JSONArray): JSONArray {
        val result = JSONArray()
        var pendingCall = false
        for (i in 0 until initial.length()) {
            val item = initial.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "function_call" -> {
                    if (pendingCall) continue // 上一个悬空，丢弃
                    pendingCall = true
                    result.put(item)
                }
                "function_call_output" -> {
                    if (item.optString("output").isEmpty()) {
                        item.put("output", "（无输出）")
                    }
                    pendingCall = false
                    result.put(item)
                }
                else -> result.put(item)
            }
        }
        return result
    }

    /* ============ 本地工具 ============ */
    private fun resolvePath(p: String): File {
        val f = File(p)
        val abs = if (f.isAbsolute) f else File(workspaceDir, p)
        val ws = workspaceDir.canonicalFile
        val target = abs.canonicalFile
        if (!target.path.startsWith(ws.path)) {
            throw IllegalArgumentException("路径越界（仅允许工作区内）: $p")
        }
        return target
    }

    private fun executeTool(name: String, argsJson: String): String {
        val args = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            JSONObject()
        }
        return try {
            when (name) {
                "read_file" -> {
                    val f = resolvePath(args.optString("path"))
                    if (!f.exists()) return "文件不存在: ${f.path}"
                    val text = f.readText()
                    val lines = text.split("\n")
                    val limit = args.optInt("limit", 0)
                    val shown = if (limit > 0) lines.take(limit) else lines
                    "文件 ${f.name} (${lines.size} 行，${f.length()} 字节)\n${shown.joinToString("\n")}"
                }
                "write_file" -> {
                    val f = resolvePath(args.optString("path"))
                    f.parentFile?.mkdirs()
                    f.writeText(args.optString("content"))
                    "已写入 ${f.path} (${args.optString("content").length} 字符)"
                }
                "list_files" -> {
                    val f = resolvePath(args.optString("path", "."))
                    if (!f.exists() || !f.isDirectory) return "目录不存在: ${f.path}"
                    f.listFiles()?.map { file ->
                        if (file.isDirectory) "📁 ${file.name}/" else "📄 ${file.name} (${file.length()} B)"
                    }?.joinToString("\n") ?: "(空目录)"
                }
                "device_info" -> deviceInfo()
                "open_url" -> {
                    val url = args.optString("url")
                    mainHandler.post {
                        try {
                            appContext.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: Exception) {
                        }
                    }
                    "已尝试打开浏览器: $url"
                }
                "ask_user" -> askUser(args)
                "run_command" -> {
                    val command = args.optString("command", "")
                    if (command.isEmpty()) return "命令为空"
                    if (needsApproval(command)) {
                        val approved = awaitApproval(command)
                        if (!approved) return "用户拒绝执行该操作"
                    }
                    runShellCommand(command, args.optInt("timeout", 120))
                }
                else -> "未知工具: $name"
            }
        } catch (e: Exception) {
            "工具执行失败: ${e.message ?: e.toString()}"
        }
    }

    private fun askUser(args: JSONObject): String {
        val questionId = "q_" + UUID.randomUUID().toString().substring(0, 8)
        val question = args.optString("question")
        val options = args.optJSONArray("options")
        val q = JSONObject()
            .put("questionId", questionId)
            .put("question", question)
            .put("options", options ?: JSONObject.NULL)
            .put("multiple", args.optBoolean("multiple"))

        val future = java.util.concurrent.CompletableFuture<String>()
        pendingQuestions[questionId] = { answer -> future.complete(answer) }

        // 通知 UI 提问（小窗/主界面）
        mainHandler.post {
            emit("question_required", JSONObject()
                .put("messageId", currentAssistantId)
                .put("partId", "ask_user")
                .put("question", q))
        }

        return try {
            future.get(30, java.util.concurrent.TimeUnit.MINUTES)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            "（已中断）"
        } catch (e: Exception) {
            "（用户未回答）"
        } finally {
            pendingQuestions.remove(questionId)
        }
    }

    /* ============ 危险命令检测（移植自 WebCode safety.ts） ============ */
    private val DANGEROUS_PATTERNS = listOf(
        Regex("""rm\s+(-rf|-[a-z]*r[a-z]*\s+-[a-z]*f[a-z]*|-[a-z]*f[a-z]*\s+-[a-z]*r[a-z]*)""", RegexOption.IGNORE_CASE),
        Regex("""mkfs""", RegexOption.IGNORE_CASE),
        Regex("""shutdown|reboot|poweroff|init\s+0""", RegexOption.IGNORE_CASE),
        Regex("""dd\s+if=.*of=/dev/(s|v)d""", RegexOption.IGNORE_CASE),
        Regex("""sudo""", RegexOption.IGNORE_CASE),
        Regex("""chmod\s+(-R\s+)?777""", RegexOption.IGNORE_CASE),
        Regex("""git\s+push\s+.*--force""", RegexOption.IGNORE_CASE),
        Regex("""git\s+reset\s+--hard""", RegexOption.IGNORE_CASE),
        Regex("""curl[^|;]*\|\s*(ba)?sh""", RegexOption.IGNORE_CASE),
        Regex("""wget[^|;]*\|\s*(ba)?sh""", RegexOption.IGNORE_CASE),
        Regex("""kill\s+(-9\s+)?-?1""", RegexOption.IGNORE_CASE),
        Regex("""find\s+/.*-delete""", RegexOption.IGNORE_CASE)
    )

    private fun needsApproval(command: String): Boolean =
        DANGEROUS_PATTERNS.any { it.containsMatchIn(command) }

    private fun runShellCommand(command: String, timeoutSec: Int): String {
        if (!com.webcode.app.termux.TermuxRuntime.isBootstrapInstalled()) {
            return "Linux 运行时未安装：请先在设置页点击「安装 Linux 运行时」（内嵌 Termux bash，约 60MB）"
        }
        return try {
            com.webcode.app.termux.LocalAgentManager.runCommand(
                appContext, command, workspaceDir,
                (if (timeoutSec > 0) timeoutSec else 120) * 1000L
            )
        } catch (e: Exception) {
            "命令执行失败: ${e.message ?: e.toString()}"
        }
    }

    private fun deviceInfo(): String {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging ?: false
        val storage = appContext.getExternalFilesDir(null)?.let {
            val stat = android.os.StatFs(it.path)
            stat.availableBytes / 1024 / 1024
        } ?: 0
        return buildString {
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("电池: $level%（充电中: $charging）")
            appendLine("可用存储: ${storage}MB")
            appendLine("工作区: ${workspaceDir.absolutePath}")
        }.trimEnd()
    }

    /* ============ UI 辅助 ============ */
    private fun emit(type: String, data: JSONObject) {
        currentListener?.onEvent(type, data)
    }

    private fun emitToolCard(partId: String, tool: String, title: String) {
        val part = Part.Tool(
            id = partId,
            tool = tool,
            title = title,
            state = "running",
            input = JSONObject(),
            output = null,
            approval = null,
            question = null
        )
        appendPart(part)
        emit("tool_start", JSONObject()
            .put("messageId", currentAssistantId)
            .put("partId", partId)
            .put("tool", tool)
            .put("title", title)
            .put("input", JSONObject()))
    }

    private fun appendPart(part: Part) {
        currentMessages.find { it.id == currentAssistantId }?.parts?.add(part)
    }

    private fun storeMessages(sid: String) {
        store.saveMessages(sid, currentMessages)
    }

    private fun storeItems(sid: String, items: JSONArray) {
        store.saveItems(sid, items)
    }

    private fun addUsage(sid: String, resp: JSONObject) {
        val usage = resp.optJSONObject("usage")
        if (usage != null) {
            val prompt = usage.optLong("input_tokens", 0)
            val completion = usage.optLong("output_tokens", 0)
            store.setUsage(sid, prompt, completion)
        }
    }

    /* ============ 控制 ============ */
    override fun cancel(sessionId: String?) {
        abort.set(true)
    }

    override fun subscribe(sessionId: String, listener: EngineListener) {
        // 本地模式运行在应用进程内，无需订阅
    }

    private fun awaitApproval(command: String): Boolean {
        val requestId = "req_" + UUID.randomUUID().toString().substring(0, 8)
        val future = CompletableFuture<Boolean>()
        pendingApprovals[requestId] = future
        mainHandler.post {
            emit("approval_required", JSONObject()
                .put("messageId", currentAssistantId)
                .put("partId", "run_command")
                .put("approval", JSONObject()
                    .put("requestId", requestId)
                    .put("command", command)
                    .put("danger", "dangerous")))
        }
        return try {
            future.get(30, java.util.concurrent.TimeUnit.MINUTES)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            false
        } finally {
            pendingApprovals.remove(requestId)
        }
    }

    override fun approve(requestId: String, approved: Boolean) {
        pendingApprovals[requestId]?.complete(approved)
    }

    override fun answer(questionId: String, answer: String) {
        pendingQuestions[questionId]?.invoke(answer)
    }

    private val SYSTEM_PROMPT = """
            你是运行在用户 Android 手机上的本地 AI 助手 "WebCode Local"。
            你可以：
            - run_command：在设备上执行 bash 命令（内嵌 Termux Linux 运行时，危险命令会自动请求用户审批）
            - read_file / write_file / list_files：在应用工作区内读写文件
            - device_info：查看设备与电池信息
            - web_search：联网搜索（服务端执行，无需客户端处理）
            - open_url：在手机浏览器打开网页
            - ask_user：需要用户决策时提问
            回答使用中文，简洁直接。
        """.trimIndent()

        private val TOOLS: List<JSONObject> = listOf(
            DirectClient.webSearchTool(),
            DirectClient.functionTool(
                "read_file", "读取工作区内的文件内容，可限制行数",
                JSONObject()
                    .put("path", JSONObject().put("type", "string").put("description", "文件路径"))
                    .put("limit", JSONObject().put("type", "integer").put("description", "最多行数")),
                listOf("path")
            ),
            DirectClient.functionTool(
                "write_file", "写入文件（覆盖已有内容）",
                JSONObject()
                    .put("path", JSONObject().put("type", "string"))
                    .put("content", JSONObject().put("type", "string")),
                listOf("path", "content")
            ),
            DirectClient.functionTool(
                "list_files", "列出目录内容",
                JSONObject().put("path", JSONObject().put("type", "string")),
                listOf("path")
            ),
            DirectClient.functionTool(
                "device_info", "查看设备信息（型号/系统/电池/存储）",
                JSONObject(),
                emptyList()
            ),
            DirectClient.functionTool(
                "open_url", "在手机浏览器打开网页",
                JSONObject().put("url", JSONObject().put("type", "string")),
                listOf("url")
            ),
            DirectClient.functionTool(
                "run_command", "在设备上执行 shell 命令（bash，内嵌 Termux 运行时）。危险命令需要用户审批",
                JSONObject()
                    .put("command", JSONObject().put("type", "string").put("description", "要执行的 bash 命令"))
                    .put("timeout", JSONObject().put("type", "integer").put("description", "超时秒数，默认 120")),
                listOf("command")
            ),
            DirectClient.functionTool(
                "ask_user", "向用户提问并等待回答",
                JSONObject()
                    .put("question", JSONObject().put("type", "string"))
                    .put("options", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))),
                listOf("question")
            )
        )

    companion object {
        const val PREFS = "webcode_direct"
        const val KEY_API_KEY = "api_key"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_REASONING = "reasoning"

        fun saveConfig(context: Context, apiKey: String, baseUrl: String, model: String, reasoning: String? = null) {
            val ed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_BASE_URL, baseUrl)
                .putString(KEY_MODEL, model)
            reasoning?.let { ed.putString(KEY_REASONING, it) }
            ed.apply()
        }

        fun loadConfig(context: Context): Triple<String, String, String> {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Triple(
                sp.getString(KEY_API_KEY, "") ?: "",
                sp.getString(KEY_BASE_URL, DirectClient.DEFAULT_BASE_URL) ?: DirectClient.DEFAULT_BASE_URL,
                sp.getString(KEY_MODEL, DirectClient.DEFAULT_MODEL) ?: DirectClient.DEFAULT_MODEL
            )
        }

        fun reasoningSetting(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_REASONING, "auto") ?: "auto"

        fun isConfigured(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return !sp.getString(KEY_API_KEY, "").isNullOrBlank()
        }
    }
}
