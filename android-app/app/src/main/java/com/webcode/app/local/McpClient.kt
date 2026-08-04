package com.webcode.app.local

import android.content.Context
import com.webcode.app.termux.DiagLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP (Model Context Protocol) 客户端
 * 支持 stdio（在 Ubuntu rootfs 内运行 npx/python 等 MCP 服务器）与 HTTP 传输。
 * JSON-RPC 2.0 over newline-delimited stdio / HTTP。
 */
class McpServer(
    val name: String,
    val type: String,        // "stdio" | "http"
    val command: String,     // stdio: 服务器命令；http: 服务器 URL
    val timeoutSec: Int = 60
)

object McpManager {

    private const val KEY_MCP_SERVERS = "mcp_servers"
    private const val PREFS = "webcode_direct"

    private val toolCache = ConcurrentHashMap<String, Pair<Long, List<JSONObject>>>()
    private val TTL_MS = 30_000L

    fun servers(context: Context): List<McpServer> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_MCP_SERVERS, "") ?: ""
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                McpServer(
                    o.optString("name"),
                    o.optString("type"),
                    o.optString("command")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveServers(context: Context, list: List<McpServer>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject()
                .put("name", s.name)
                .put("type", s.type)
                .put("command", s.command))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MCP_SERVERS, arr.toString()).apply()
        toolCache.clear()
    }

    /** 获取全部 MCP 工具定义（带缓存），失败返回空并记日志 */
    fun allTools(context: Context): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        for (s in servers(context)) {
            try {
                val cached = toolCache[s.name]
                if (cached != null && System.currentTimeMillis() - cached.first < TTL_MS) {
                    result.addAll(cached.second)
                    continue
                }
                val tools = listTools(context, s)
                toolCache[s.name] = System.currentTimeMillis() to tools
                result.addAll(tools)
            } catch (e: Exception) {
                DiagLog.log(context, "MCP", "服务器 ${s.name} 工具列表失败: ${e.message}")
            }
        }
        return result
    }

    /** 调用 MCP 工具，返回字符串输出 */
    fun callTool(context: Context, serverName: String, toolName: String, args: JSONObject): String {
        val server = servers(context).find { it.name == serverName }
            ?: return "MCP 服务器不存在: $serverName"
        return try {
            val result = when (server.type) {
                "stdio" -> callStdio(server, "tools/call", JSONObject()
                    .put("name", toolName)
                    .put("arguments", args))
                else -> callHttp(server, "tools/call", JSONObject()
                    .put("name", toolName)
                    .put("arguments", args))
            }
            result.optString("output").ifEmpty { result.toString() }
        } catch (e: Exception) {
            DiagLog.log(context, "MCP", "工具调用失败 ${serverName}/$toolName: ${e.message}")
            "MCP 工具调用失败: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun listTools(context: Context, server: McpServer): List<JSONObject> {
        val result = when (server.type) {
            "stdio" -> callStdio(server, "tools/list", JSONObject())
            else -> callHttp(server, "tools/list", JSONObject())
        }
        val tools = result.optJSONArray("tools") ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val rawSchema = t.optJSONObject("inputSchema")
            out.add(
                DirectClient.functionTool(
                    "mcp_${server.name}_${t.optString("name")}",
                    "[MCP:${server.name}] ${t.optString("description", "")}",
                    sanitizeSchema(rawSchema) ?: JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject()),
                    emptyList()
                )
            )
        }
        return out
    }

    /**
     * 净化 MCP 返回的 JSON Schema：剔除 DeepSeek 不接受的字段，
     * 避免 "Invalid schema ... is not of types boolean" 类 400。
     */
    private fun sanitizeSchema(schema: JSONObject?): JSONObject? {
        if (schema == null) return null
        val out = JSONObject()
        val keys = schema.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (k) {
                // 非标准/不支持的元数据字段直接剔除
                "\$schema", "\$id", "\$ref", "\$defs", "definitions",
                "examples", "default", "title", "format" -> {
                }
                // additionalProperties 只接受布尔（DeepSeek 校验）
                "additionalProperties" -> {
                    out.put(k, true)
                }
                // 递归清理子 schema
                "properties", "items", "patternProperties" -> {
                    val v = schema.opt(k)
                    if (v is JSONObject) {
                        val cleaned = JSONObject()
                        val sub = v.keys()
                        while (sub.hasNext()) {
                            val sk = sub.next()
                            val sv = v.opt(sk)
                            if (sv is JSONObject) cleaned.put(sk, sanitizeSchema(sv) ?: JSONObject())
                            else cleaned.put(sk, sv)
                        }
                        out.put(k, cleaned)
                    } else {
                        out.put(k, v)
                    }
                }
                // 组合模式 DeepSeek 不一定支持，剔除避免 400
                "oneOf", "anyOf", "allOf", "not" -> {
                }
                else -> out.put(k, schema.opt(k))
            }
        }
        return out
    }

    /* ============ stdio 传输（在 rootfs 内运行服务器进程） ============ */
    private fun callStdio(server: McpServer, method: String, params: JSONObject): JSONObject {
        val proc = startStdioProcess(server)
        try {
            val req = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", method)
                .put("params", params)
            val writer = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
            writer.write(req.toString() + "\n")
            writer.flush()

            val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
            val deadline = System.currentTimeMillis() + server.timeoutSec * 1000L
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    val resp = JSONObject(line)
                    if (resp.has("id")) {
                        if (resp.has("error")) {
                            throw RuntimeException(resp.optJSONObject("error")?.optString("message") ?: "MCP 错误")
                        }
                        val r = resp.optJSONObject("result") ?: JSONObject()
                        if (method == "tools/call") {
                            // 提取文本内容
                            val content = r.optJSONArray("content")
                            if (content != null) {
                                val sb = StringBuilder()
                                for (i in 0 until content.length()) {
                                    val c = content.optJSONObject(i)
                                    if (c?.optString("type") == "text") {
                                        sb.append(c.optString("text"))
                                    } else if (c?.optString("type") == "resource") {
                                        sb.append(c.optJSONObject("resource")?.optString("text") ?: "")
                                    }
                                }
                                return JSONObject().put("output", sb.toString())
                            }
                            if (r.has("output")) return JSONObject().put("output", r.optString("output"))
                            return r
                        }
                        return r
                    }
                } catch (e: Exception) {
                    // 非 JSON 行忽略
                }
            }
            throw RuntimeException("MCP 服务器超时（${server.timeoutSec}s）")
        } finally {
            try {
                proc.destroy()
            } catch (e: Exception) {
            }
        }
    }

    private fun startStdioProcess(server: McpServer): Process {
        // 在 Ubuntu rootfs 中通过 proot 启动 MCP 服务器进程（npx/python 等）
        val ctx = com.webcode.app.termux.TermuxRuntime.appContextOrNull()
            ?: throw RuntimeException("应用未初始化")
        val pb = com.webcode.app.termux.LocalAgentManager.mcpSpawn(ctx, server.command)
        val p = pb.start()
        // MCP 初始化握手
        val initReq = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", UUID.randomUUID().toString())
            .put("method", "initialize")
            .put("params", JSONObject()
                .put("protocolVersion", "2025-03-26")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "WebCodeLocal").put("version", "3.0")))
        val writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
        writer.write(initReq.toString() + "\n")
        writer.flush()
        val reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            try {
                val resp = JSONObject(line)
                if (resp.has("id")) break // 初始化完成
            } catch (e: Exception) {
            }
        }
        return p
    }

    /* ============ HTTP 传输 ============ */
    private fun callHttp(server: McpServer, method: String, params: JSONObject): JSONObject {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", UUID.randomUUID().toString())
            .put("method", method)
            .put("params", params)
        val conn = URL(server.command).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = server.timeoutSec * 1000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.outputStream.write(body.toString().toByteArray())
            conn.outputStream.close()

            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code: ${raw.take(200)}")
            }
            // 可能是 SSE 格式，取最后一条 data
            val dataLine = raw.lineSequence()
                .filter { it.startsWith("data:") }
                .lastOrNull()?.substring(5)?.trim()
            val json = if (dataLine != null && dataLine.startsWith("{")) {
                JSONObject(dataLine)
            } else {
                JSONObject(raw)
            }
            if (json.has("error")) {
                throw RuntimeException(json.optJSONObject("error")?.optString("message") ?: "MCP 错误")
            }
            val r = json.optJSONObject("result") ?: JSONObject()
            if (method == "tools/call") {
                val content = r.optJSONArray("content")
                if (content != null) {
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val c = content.optJSONObject(i)
                        if (c?.optString("type") == "text") sb.append(c.optString("text"))
                    }
                    return JSONObject().put("output", sb.toString())
                }
                if (r.has("output")) return JSONObject().put("output", r.optString("output"))
            }
            return r
        } finally {
            conn.disconnect()
        }
    }
}
