package com.webcode.app.local

import android.content.Context
import com.webcode.app.api.Session
import com.webcode.app.api.SessionMessage
import com.webcode.app.api.SessionMeta
import com.webcode.app.api.parseMessage
import com.webcode.app.api.parseSessionMeta
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 本地会话存储：items（API 历史）+ 消息快照（UI 渲染），全部存本机 JSON */
class LocalStore(context: Context) {

    private val dir = File(context.filesDir, "local_sessions").apply { mkdirs() }

    private fun file(id: String): File = File(dir, "$id.json")

    fun create(title: String?): SessionMeta {
        val id = "s_" + UUID.randomUUID().toString().substring(0, 13)
        val now = System.currentTimeMillis()
        val doc = JSONObject()
            .put("id", id)
            .put("title", title ?: "新对话")
            .put("createdAt", now)
            .put("updatedAt", now)
            .put("items", JSONArray())
            .put("messages", JSONArray())
        file(id).writeText(doc.toString(2))
        return parseSessionMeta(metaOf(doc))
    }

    fun list(): List<SessionMeta> =
        dir.listFiles()?.filter { it.name.endsWith(".json") }?.mapNotNull { f ->
            try {
                parseSessionMeta(metaOf(JSONObject(f.readText())))
            } catch (e: Exception) {
                null
            }
        }?.sortedByDescending { it.updatedAt } ?: emptyList()

    private fun metaOf(doc: JSONObject): JSONObject {
        val meta = JSONObject()
            .put("id", doc.optString("id"))
            .put("title", doc.optString("title"))
            .put("createdAt", doc.optLong("createdAt"))
            .put("updatedAt", doc.optLong("updatedAt"))
            .put("usage", doc.optJSONObject("usage"))
        if (doc.has("partition") && !doc.isNull("partition")) {
            meta.put("partition", doc.optString("partition"))
        }
        return meta
    }

    fun get(id: String): Session? = try {
        val doc = JSONObject(file(id).readText())
        val msgs = JSONArray()
        doc.optJSONArray("messages")?.let { a ->
            for (i in 0 until a.length()) {
                a.optJSONObject(i)?.let { msgs.put(it) }
            }
        }
        Session(
            id = doc.optString("id"),
            title = doc.optString("title"),
            createdAt = doc.optLong("createdAt"),
            updatedAt = doc.optLong("updatedAt"),
            messages = (0 until msgs.length()).mapNotNull { i ->
                try {
                    val m = msgs.optJSONObject(i)?.let { parseMessage(it) }
                    // 上次运行中断留下的 running 工具：归一化为"已中断"，避免重启后一直转圈
                    m?.parts?.filterIsInstance<com.webcode.app.api.Part.Tool>()?.forEach { p ->
                        if (p.state == "running") {
                            p.state = "error"
                            p.output = "（应用中断，结果未知）"
                        }
                    }
                    m
                } catch (e: Exception) {
                    null
                }
            },
            usage = doc.optJSONObject("usage")
        )
    } catch (e: Exception) {
        null
    }

    fun items(id: String): JSONArray = try {
        JSONObject(file(id).readText()).optJSONArray("items") ?: JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }

    fun setPartition(id: String, partition: String?) {
        update(id) { doc ->
            if (partition == null) {
                doc.remove("partition")
            } else {
                doc.put("partition", partition)
            }
        }
    }

    fun partitions(): List<String> =
        list()
            .mapNotNull { it.partition }
            .distinct()
            .sorted()

    /** 释放分区：其中会话移回默认（partition=null） */
    fun releasePartition(name: String) {
        for (s in list()) {
            if (s.partition == name) setPartition(s.id, null)
        }
    }

    /** 删除分区：连同其中会话一起删除 */
    fun deletePartition(name: String) {
        for (s in list()) {
            if (s.partition == name) delete(s.id)
        }
    }

    fun rename(id: String, title: String) {
        update(id) { it.put("title", title) }
    }

    fun delete(id: String) {
        file(id).delete()
    }

    fun update(id: String, fn: (JSONObject) -> Unit) {
        try {
            val doc = JSONObject(file(id).readText())
            fn(doc)
            doc.put("updatedAt", System.currentTimeMillis())
            file(id).writeText(doc.toString(2))
        } catch (e: Exception) {
        }
    }

    fun saveMessages(id: String, messages: List<SessionMessage>) {
        update(id) { doc ->
            val a = JSONArray()
            for (m in messages) {
                a.put(messageToJson(m))
            }
            doc.put("messages", a)
        }
    }

    fun saveItems(id: String, items: JSONArray) {
        update(id) { doc -> doc.put("items", items) }
    }

    fun setUsage(id: String, prompt: Long, completion: Long) {
        update(id) { doc ->
            doc.put("usage", JSONObject()
                .put("promptTokens", prompt)
                .put("completionTokens", completion))
        }
    }

    private fun messageToJson(m: SessionMessage): JSONObject {
        val parts = JSONArray()
        for (p in m.parts) {
            when (p) {
                is com.webcode.app.api.Part.Text -> parts.put(JSONObject().put("type", "text").put("text", p.text))
                is com.webcode.app.api.Part.Thinking -> parts.put(JSONObject().put("type", "thinking").put("text", p.text))
                is com.webcode.app.api.Part.Tool -> parts.put(
                    JSONObject()
                        .put("type", "tool")
                        .put("id", p.id)
                        .put("tool", p.tool)
                        .put("title", p.title)
                        .put("state", p.state)
                        .put("input", p.input ?: JSONObject())
                        .put("output", p.output ?: "")
                )
            }
        }
        return JSONObject()
            .put("id", m.id)
            .put("role", m.role)
            .put("parts", parts)
            .put("createdAt", m.createdAt)
    }
}
