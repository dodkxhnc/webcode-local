package com.webcode.app.api

import org.json.JSONArray
import org.json.JSONObject

data class ToolApproval(
    val requestId: String,
    val command: String,
    val danger: String
)

data class ToolQuestion(
    val questionId: String,
    val question: String,
    val options: List<String>?,
    val multiple: Boolean
)

sealed class Part {
    data class Text(var text: String) : Part()
    data class Thinking(var text: String) : Part()
    data class Tool(
        val id: String,
        val tool: String,
        val title: String,
        var state: String,
        val input: JSONObject?,
        var output: String?,
        var approval: ToolApproval?,
        var question: ToolQuestion?
    ) : Part()
}

data class SessionMessage(
    var id: String,
    val role: String,
    val parts: MutableList<Part>,
    val createdAt: Long
)

data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<SessionMessage>,
    val usage: JSONObject?
)

data class SessionMeta(
    val id: String,
    var title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val usage: JSONObject?
)

data class Usage(
    val totalTokens: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val requestCount: Long,
    val sessionUsage: JSONObject?
)

data class WorkspaceInfo(
    val workspace: String,
    val model: String,
    val mock: Boolean,
    val hasApiKey: Boolean
)

data class SettingsInfo(
    val provider: String,
    val baseUrl: String,
    val model: String,
    val authType: String,
    val apiKeyHeader: String,
    val thinking: String?,
    val reasoningEffort: String?,
    val maxSteps: Int?,
    val hasApiKey: Boolean,
    val apiKeySet: Boolean,
    val envOverridden: Boolean
)

object Json {
    fun obj(block: JSONObject.() -> Unit): JSONObject = JSONObject().apply(block)
    fun arr(block: JSONArray.() -> Unit): JSONArray = JSONArray().apply(block)
}

fun parseApproval(o: JSONObject): ToolApproval =
    ToolApproval(
        requestId = o.optString("requestId"),
        command = o.optString("command"),
        danger = o.optString("danger")
    )

fun parseQuestion(o: JSONObject): ToolQuestion {
    val opts = o.optJSONArray("options")
    return ToolQuestion(
        questionId = o.optString("questionId"),
        question = o.optString("question"),
        options = if (opts == null) null else (0 until opts.length()).map { opts.getString(it) },
        multiple = o.optBoolean("multiple")
    )
}

fun parsePart(o: JSONObject): Part? = when (o.optString("type")) {
    "text" -> Part.Text(o.optString("text"))
    "thinking" -> Part.Thinking(o.optString("text"))
    "tool" -> Part.Tool(
        id = o.optString("id"),
        tool = o.optString("tool"),
        title = o.optString("title"),
        state = o.optString("state", "running"),
        input = o.optJSONObject("input"),
        output = o.optString("output", "").ifEmpty { null },
        approval = o.optJSONObject("approval")?.let { parseApproval(it) },
        question = o.optJSONObject("question")?.let { parseQuestion(it) }
    )
    else -> null
}

fun parseMessage(o: JSONObject): SessionMessage {
    val parts = mutableListOf<Part>()
    o.optJSONArray("parts")?.let { a ->
        for (i in 0 until a.length()) {
            a.optJSONObject(i)?.let { parsePart(it) }?.let { parts.add(it) }
        }
    }
    return SessionMessage(
        id = o.optString("id"),
        role = o.optString("role"),
        parts = parts,
        createdAt = o.optLong("createdAt")
    )
}

fun parseSessionMeta(o: JSONObject): SessionMeta =
    SessionMeta(
        id = o.optString("id"),
        title = o.optString("title"),
        createdAt = o.optLong("createdAt"),
        updatedAt = o.optLong("updatedAt"),
        usage = o.optJSONObject("usage")
    )

fun parseSession(o: JSONObject): Session {
    val messages = mutableListOf<SessionMessage>()
    o.optJSONArray("messages")?.let { a ->
        for (i in 0 until a.length()) {
            a.optJSONObject(i)?.let { messages.add(parseMessage(it)) }
        }
    }
    return Session(
        id = o.optString("id"),
        title = o.optString("title"),
        createdAt = o.optLong("createdAt"),
        updatedAt = o.optLong("updatedAt"),
        messages = messages,
        usage = o.optJSONObject("usage")
    )
}
