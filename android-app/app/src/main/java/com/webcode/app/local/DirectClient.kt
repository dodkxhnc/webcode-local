package com.webcode.app.local

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DeepSeek Responses API 客户端（直连模型供应商，不经过任何中间服务器）
 * base_url: https://api.deepseek.com/v1/responses
 * 无状态 API：客户端保存全部输入 items 历史
 *
 * 流式事件（SSE，无 [DONE]，以 response.completed / incomplete / failed 结束）：
 *  response.output_text.delta / reasoning_text.delta / function_call_arguments.delta
 *  response.output_item.added / response.output_item.done
 *  response.web_search_call.* （服务端搜索状态）
 *  response.completed（携带完整 response 对象：output items + usage）
 */
class DirectClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com",
    private val model: String = "deepseek-v4-flash"
) {

    class ResponseEvent(val type: String, val data: JSONObject)
    class StreamResult(
        val completed: Boolean,
        val response: JSONObject?,
        val error: String?,
        val truncated: Boolean = false
    )

    interface Listener {
        fun onEvent(ev: ResponseEvent)
        fun onError(message: String)
    }

    /**
     * 流式调用 Responses API
     * @param input 输入 items（字符串或 item 列表）
     * @param instructions system 提示
     * @param tools 工具定义（function / web_search）
     * @param listener 事件回调（同一线程，需自行切主线程）
     * @param abort 取消标记
     * @param reasoningEffort 思考强度：null=自动 / none=非思考 / low / medium / high
     */
    fun create(
        input: Any,
        instructions: String,
        tools: List<JSONObject>?,
        listener: Listener,
        abort: AtomicBoolean,
        reasoningEffort: String? = null
    ): StreamResult {
        val body = JSONObject()
        body.put("model", model)
        body.put("instructions", instructions)
        body.put("input", input)
        body.put("stream", true)
        if (!tools.isNullOrEmpty()) body.put("tools", JSONArray(tools))
        if (reasoningEffort != null && reasoningEffort.isNotEmpty()) {
            body.put("reasoning", JSONObject().put("effort", reasoningEffort))
        }
        body.put("max_output_tokens", 8192)

        var conn: HttpURLConnection? = null
        try {
            conn = URL("${baseUrl.trimEnd('/')}/v1/responses").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 300_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.outputStream.write(body.toString().toByteArray())
            conn.outputStream.close()

            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
                // 400/500 详细落盘，便于诊断
                logApiError(code, err, body.toString())
                return StreamResult(false, null, "HTTP $code: ${err.take(300)}")
            }

            var buffer = StringBuilder()
            val reader = BufferedReader(conn.inputStream.reader(Charsets.UTF_8))
            var finalResponse: JSONObject? = null
            var done = false
            var truncated = false

            while (true) {
                if (abort.get()) {
                    return StreamResult(false, finalResponse, "已中止")
                }
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    val block = buffer.toString()
                    buffer = StringBuilder()
                    handleBlock(block, listener)?.let { ev ->
                        when (ev.type) {
                            "response.completed" -> {
                                finalResponse = ev.data.optJSONObject("response")
                                done = true
                            }
                            "response.incomplete" -> {
                                finalResponse = ev.data.optJSONObject("response")
                                truncated = true
                                done = true
                            }
                            "response.failed" -> {
                                val resp = ev.data.optJSONObject("response")
                                val err = resp?.optJSONObject("error")?.optString("message")
                                    ?: "响应失败"
                                listener.onError(err)
                                return StreamResult(false, resp, err)
                            }
                        }
                    }
                    if (done) break
                } else {
                    buffer.append(line).append('\n')
                }
            }
            if (!done) {
                com.webcode.app.termux.DiagLog.log(null, "API", "SSE 流中断（未收到完成事件）model=$model")
                return StreamResult(false, finalResponse, "流中断")
            }
            return StreamResult(true, finalResponse, null, truncated)
        } catch (e: Exception) {
            com.webcode.app.termux.DiagLog.log(null, "API", "请求异常: ${e.message ?: e.javaClass.simpleName} url=$baseUrl model=$model")
            return StreamResult(false, null, e.message ?: "网络错误")
        } finally {
            conn?.disconnect()
        }
    }

    private fun logApiError(code: Int, respBody: String, reqBody: String) {
        com.webcode.app.termux.DiagLog.log(null, "API", "HTTP $code 响应: ${respBody.take(500)}")
        try {
            val ctx = com.webcode.app.termux.TermuxRuntime.appContextOrNull() ?: return
            val f = java.io.File(ctx.getExternalFilesDir(null), "api-errors.log")
            f.parentFile?.mkdirs()
            f.appendText(
                "时间: ${System.currentTimeMillis()}\nHTTP $code\n请求(截断4000):\n" +
                    reqBody.take(4000) + "\n响应:\n" + respBody.take(2000) + "\n\n==========\n"
            )
        } catch (e: Exception) {
        }
    }

    /** 解析一个 SSE 块（event: 与 data: 行），生成事件 */
    private fun handleBlock(block: String, listener: Listener): ResponseEvent? {
        var eventName = ""
        var dataLine = ""
        for (line in block.lines()) {
            val t = line.trim()
            when {
                t.startsWith("event:") -> eventName = t.substring(6).trim()
                t.startsWith("data:") -> dataLine = t.substring(5).trim()
            }
        }
        if (eventName.isEmpty() || dataLine.isEmpty()) return null
        val json = try {
            JSONObject(dataLine)
        } catch (e: Exception) {
            return null
        }
        val ev = ResponseEvent(eventName, json)
        listener.onEvent(ev)
        return ev
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-v4-flash"

        /** 服务端执行工具：联网搜索 */
        fun webSearchTool(): JSONObject = JSONObject().put("type", "web_search")

        fun functionTool(
            name: String,
            description: String,
            properties: JSONObject,
            required: List<String>
        ): JSONObject {
            val params = JSONObject()
            params.put("type", "object")
            params.put("properties", properties)
            params.put("required", JSONArray(required))
            return JSONObject()
                .put("type", "function")
                .put("name", name)
                .put("description", description)
                .put("parameters", params)
        }

        fun messageItem(role: String, text: String): JSONObject =
            JSONObject()
                .put("type", "message")
                .put("role", role)
                .put("content", JSONArray().put(
                    JSONObject().put("type", "output_text").put("text", text)
                ))

        fun functionCallOutputItem(callId: String, output: String): JSONObject =
            JSONObject()
                .put("type", "function_call_output")
                .put("call_id", callId)
                .put("output", output.trim().ifEmpty { "（无输出）" })

        fun functionCallItem(callId: String, name: String, args: String): JSONObject =
            JSONObject()
                .put("type", "function_call")
                .put("id", callId)
                .put("call_id", callId)
                .put("name", name)
                .put("arguments", args.ifEmpty { "{}" })
    }
}
