package com.webcode.app.api

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

class Api private constructor(val baseUrl: String, val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private fun req(path: String): okhttp3.Request =
        Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $token")
            .build()

    private fun get(path: String): okhttp3.Request = req(path)

    private fun post(path: String, body: JSONObject): okhttp3.Request =
        req(path).newBuilder().post(jsonBody(body)).build()

    private fun patch(path: String, body: JSONObject): okhttp3.Request =
        req(path).newBuilder().patch(jsonBody(body)).build()

    private fun put(path: String, body: JSONObject): okhttp3.Request =
        req(path).newBuilder().put(jsonBody(body)).build()

    private fun delete(path: String): okhttp3.Request =
        req(path).newBuilder().delete().build()

    private fun jsonBody(o: JSONObject): okhttp3.RequestBody =
        o.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun execute(r: Request): String {
        client.newCall(r).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiException(resp.code, body)
            return body
        }
    }

    private fun parseOrNull(s: String): JSONObject? = try {
        JSONObject(s)
    } catch (e: Exception) {
        null
    }

    fun checkAuth(): Boolean = try {
        client.newCall(get("/api/auth/me")).execute().use {
            it.isSuccessful && it.code == 200
        }
    } catch (e: IOException) {
        false
    }

    fun login(): Boolean = try {
        client.newCall(post("/api/auth/login", JSONObject().put("token", token)))
            .execute().use { it.isSuccessful }
    } catch (e: IOException) {
        false
    }

    fun listSessions(): List<SessionMeta> {
        return try {
            val data = parseOrNull(execute(get("/api/sessions"))) ?: return emptyList()
            val arr = data.optJSONArray("sessions") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { parseSessionMeta(it) }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(-1, e.message ?: "网络错误")
        }
    }

    fun createSession(title: String? = null): SessionMeta {
        val body = JSONObject()
        title?.let { body.put("title", it) }
        val data = parseOrNull(execute(
            post("/api/sessions", body)
        )) ?: throw ApiException(-1, "响应无效")
        return parseSessionMeta(data.optJSONObject("session") ?: data)
    }

    fun getSession(id: String): Session {
        val data = parseOrNull(execute(get("/api/sessions/$id")))
            ?: throw ApiException(-1, "响应无效")
        return parseSession(data.optJSONObject("session") ?: data)
    }

    fun renameSession(id: String, title: String): Boolean {
        val body = JSONObject().put("title", title)
        return try {
            execute(patch("/api/sessions/$id", body))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteSession(id: String) {
        execute(delete("/api/sessions/$id"))
    }

    fun getUsage(sessionId: String?): Usage? {
        return try {
            val q = if (sessionId != null) "?sessionId=${sessionId}" else ""
            val data = parseOrNull(execute(get("/api/usage$q"))) ?: return null
            val u = data.optJSONObject("usage")
            Usage(
                totalTokens = u?.optLong("totalTokens") ?: 0,
                promptTokens = u?.optLong("promptTokens") ?: 0,
                completionTokens = u?.optLong("completionTokens") ?: 0,
                requestCount = u?.optLong("requestCount") ?: 0,
                sessionUsage = data.optJSONObject("sessionUsage")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun getWorkspace(): WorkspaceInfo? {
        return try {
            val data = parseOrNull(execute(get("/api/workspace"))) ?: return null
            WorkspaceInfo(
                workspace = data.optString("workspace"),
                model = data.optString("model"),
                mock = data.optBoolean("mock"),
                hasApiKey = data.optBoolean("hasApiKey")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun getSettings(): SettingsInfo? {
        return try {
            val d = parseOrNull(execute(get("/api/settings"))) ?: return null
        SettingsInfo(
            provider = d.optString("provider"),
            baseUrl = d.optString("baseUrl"),
            model = d.optString("model"),
            authType = d.optString("authType"),
            apiKeyHeader = d.optString("apiKeyHeader"),
            thinking = if (d.isNull("thinking")) null else d.optString("thinking"),
            reasoningEffort = if (d.isNull("reasoningEffort")) null else d.optString("reasoningEffort"),
            maxSteps = if (d.isNull("maxSteps")) null else d.optInt("maxSteps"),
            hasApiKey = d.optBoolean("hasApiKey"),
            apiKeySet = d.optBoolean("apiKeySet"),
            envOverridden = d.optBoolean("envOverridden")
        )
    } catch (e: Exception) {
        null
    }
    }

    fun saveSettings(o: JSONObject): String? = try {
        val r = put("/api/settings", o)
        execute(r)
        null
    } catch (e: ApiException) {
        e.message ?: "保存失败"
    } catch (e: IOException) {
        e.message ?: "网络错误"
    }

    fun approve(requestId: String, approved: Boolean): Boolean = try {
        val body = JSONObject().put("requestId", requestId).put("approved", approved)
        execute(post("/api/approve", body))
        true
    } catch (e: Exception) {
        false
    }

    fun answer(questionId: String, answer: String): Boolean = try {
        val body = JSONObject().put("questionId", questionId).put("answer", answer)
        execute(post("/api/answer", body))
        true
    } catch (e: Exception) {
        false
    }

    fun abort(sessionId: String): Boolean = try {
        val body = JSONObject().put("sessionId", sessionId)
        execute(post("/api/chat/abort", body))
        true
    } catch (e: Exception) {
        false
    }

    /** 打开 SSE 流。返回的 EventSource 由调用方 cancel()。 */
    fun openStream(
        sessionId: String?,
        content: String?,
        listener: EventSourceListener
    ): EventSource {
        val request = if (content != null) {
            val body = JSONObject()
            sessionId?.let { body.put("sessionId", it) }
            body.put("content", content)
            post("/api/chat", body)
        } else {
            val u = req("/api/chat").url.newBuilder()
                .addQueryParameter("sessionId", sessionId ?: "")
                .build()
            req("/api/chat").newBuilder().url(u).get().build()
        }
        val factory = EventSources.createFactory(client)
        return factory.newEventSource(request, listener)
    }

    companion object {
        const val PREF_NAME = "webcode_prefs"
        const val KEY_SERVER = "server_url"
        const val KEY_TOKEN = "token"

        @Volatile
        private var instance: Api? = null

        fun get(): Api = instance ?: throw IllegalStateException("Api 未初始化")

        fun set(api: Api) {
            instance = api
        }

        fun forCredentials(serverUrl: String, token: String): Api = Api(serverUrl, token)

        fun saveCredentials(context: Context, serverUrl: String, token: String) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER, serverUrl)
                .putString(KEY_TOKEN, token)
                .apply()
        }

        fun serverUrlDefault(context: Context): String =
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SERVER, "http://172.31.178.190:3456") ?: ""

        fun tokenDefault(context: Context): String =
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TOKEN, "") ?: ""

        fun clearCredentials(context: Context) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }

        fun init(context: Context): Api {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val base = sp.getString(KEY_SERVER, "") ?: ""
            val token = sp.getString(KEY_TOKEN, "") ?: ""
            instance = Api(base, token)
            return instance!!
        }

        fun hasCredentials(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return !(sp.getString(KEY_SERVER, "").isNullOrBlank() ||
                    sp.getString(KEY_TOKEN, "").isNullOrBlank())
        }
    }
}
