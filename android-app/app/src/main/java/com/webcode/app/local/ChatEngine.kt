package com.webcode.app.local

import com.webcode.app.api.Session
import com.webcode.app.api.SessionMeta
import com.webcode.app.api.Usage
import com.webcode.app.api.WorkspaceInfo
import com.webcode.app.api.SettingsInfo
import org.json.JSONObject

/**
 * 聊天引擎抽象：云端模式（WebCode 服务器）/ 本地直连模式（DeepSeek Responses API）
 * 事件格式与 WebCode SSE 完全兼容，UI 层无需感知模式差异
 */
interface ChatEngine {

    val isLocal: Boolean

    fun listSessions(): List<SessionMeta>

    fun createSession(title: String? = null): SessionMeta

    fun getSession(id: String): Session?

    fun renameSession(id: String, title: String): Boolean

    fun deleteSession(id: String)

    fun usage(sessionId: String?): Usage?

    fun workspaceInfo(): WorkspaceInfo?

    fun settingsInfo(): SettingsInfo?

    fun saveSettings(o: JSONObject): String?

    /** 发送消息并开始 Agent 运行 */
    fun start(sessionId: String?, content: String, listener: EngineListener)

    /** 停止当前运行 */
    fun cancel(sessionId: String?)

    /** 订阅正在运行的 Agent（云端断线重连用；本地模式无操作） */
    fun subscribe(sessionId: String, listener: EngineListener)

    fun approve(requestId: String, approved: Boolean)

    fun answer(questionId: String, answer: String)
}

object Engines {
    @Volatile
    private var local: LocalEngine? = null

    /** 本地直连模式（唯一模式） */
    fun current(context: android.content.Context): ChatEngine =
        local ?: LocalEngine(context).also { local = it }
}

interface EngineListener {
    /** type 与 payload 与 WebCode SSE 事件一致 */
    fun onEvent(type: String, data: JSONObject)
    fun onStreamError(message: String)
}

object EngineListenerAdapter {
    fun listener(
        onEvent: (String, JSONObject) -> Unit = { _, _ -> },
        onStreamError: (String) -> Unit = {}
    ): EngineListener = object : EngineListener {
        override fun onEvent(type: String, data: JSONObject) = onEvent(type, data)
        override fun onStreamError(message: String) = onStreamError(message)
    }
}
