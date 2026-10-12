package com.glpmate.native_app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 后端客户端：只用 java.net + org.json，不引 Retrofit/OkHttp。
 *
 * 理由：接口只有十来个，全是同源 JSON；引一个 HTTP 框架带来的收益小于
 * 它带来的体积和版本管理成本。真需要拦截器/重试时再换不迟。
 *
 * 地址选择（重要）：
 *   真机上后端跑在 APK 内，地址是 127.0.0.1:8848。
 *   模拟器上 127.0.0.1 指向模拟器自己，所以开发时后端跑在电脑上，
 *   要用 10.0.2.2（模拟器映射到宿主机）。两个都试，谁通用谁——
 *   这样同一个 APK 在模拟器和真机都能连上，不需要改代码或 adb reverse。
 */
class BackendClient(
    private val port: Int = 8848,
    private val timeoutMs: Int = 10_000,
) {
    /** 实际可用的 baseUrl；null 表示还没探测到。 */
    @Volatile
    var baseUrl: String? = null
        private set

    private val TAG = "GlpNet"

    private val candidates = listOf(
        "http://127.0.0.1:$port",
        "http://10.0.2.2:$port",          // Android 模拟器 → 宿主机
    )

    suspend fun connect(): Result<String> = withContext(Dispatchers.IO) {
        var lastError: String? = null
        for (candidate in candidates) {
            try {
                val body = rawGet("$candidate/api/health")
                Log.i(TAG, "探测 $candidate → ${body.take(60)}")
                if (body.contains("\"ok\"")) {
                    baseUrl = candidate
                    Log.i(TAG, "已连接后端：$candidate")
                    return@withContext Result.success(candidate)
                }
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "探测 $candidate 失败：$lastError")
            }
        }
        Log.e(TAG, "两个地址都连不上，最后一次错误：$lastError")
        Result.failure(IllegalStateException(lastError ?: "两个地址都连不上"))
    }

    suspend fun bootstrap(): Bootstrap = getJson("/api/bootstrap").let { Bootstrap.from(it) }

    suspend fun records(
        types: List<String>? = null,
        start: String? = null,
        end: String? = null,
        limit: Int = 200,
        order: String = "desc",
    ): List<RecordView> = withContext(Dispatchers.IO) {
        val query = buildString {
            append("?limit=").append(limit).append("&order=").append(order)
            if (!types.isNullOrEmpty()) append("&types=").append(types.joinToString(","))
            if (start != null) append("&start=").append(start)
            if (end != null) append("&end=").append(end)
        }
        val arr = getJson("/api/records$query").optJSONArray("items")
        val out = mutableListOf<RecordView>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(RecordView.from(it)) }
            }
        }
        out
    }

    suspend fun levelChart(): LevelInfo? = LevelInfo.from(getJson("/api/charts/level"))

    suspend fun weightChart(days: Int = 90): WeightInfo {
        val o = getJson("/api/charts/weight?days=$days")
        val series = mutableListOf<SeriesPoint>()
        val arr = o.optJSONArray("series")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                series.add(SeriesPoint(p.optString("t"), p.optDouble("v", 0.0)))
            }
        }
        return WeightInfo(
            latest = series.lastOrNull()?.v,
            start = series.firstOrNull()?.v,
            delta = o.optDouble("delta").takeIf { !it.isNaN() },
            goal = o.optDouble("goal").takeIf { !it.isNaN() },
            series = series,
        )
    }

    suspend fun summary(period: String = "week"): JSONObject =
        getJson("/api/records/summary?period=$period")

    suspend fun calendar(month: String): JSONObject = getJson("/api/calendar?month=$month")

    suspend fun settings(): JSONObject = getJson("/api/settings")

    /** 药物下拉选项（含每支药的剂量阶梯、半衰期、置信度）。 */
    suspend fun drugOptions(): List<DrugOption> = withContext(Dispatchers.IO) {
        val arr = getJson("/api/drugs").optJSONArray("options") ?: return@withContext emptyList()
        val out = mutableListOf<DrugOption>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(DrugOption.from(it)) }
        }
        out
    }

    suspend fun updateSettings(payload: JSONObject): JSONObject = postJson("/api/settings", payload, "PUT")

    /**
     * 测试模型连接。
     *
     * 后端约定：**测试失败也返回 HTTP 200**，用 `ok:false` + `error` 表达，
     * 因为它已经把失败原因归类成中文了。所以这里不能按 HTTP 状态判断成败。
     */
    suspend fun testAiConnection(overrides: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
            for ((k, v) in overrides) if (v.isNotBlank()) body.put(k, v)
            postJson("/api/settings/test", body, "POST")
        }

    suspend fun drugs(): JSONObject = getJson("/api/drugs")

    suspend fun efficacy(drugId: String? = null): JSONObject =
        getJson("/api/efficacy" + if (drugId.isNullOrBlank()) "" else "?drug=${enc(drugId)}")

    suspend fun coachMessages(): Pair<List<JSONObject>, Quota> = withContext(Dispatchers.IO) {
        val o = getJson("/api/coach/messages")
        val list = mutableListOf<JSONObject>()
        o.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
        }
        list to Quota.from(o.optJSONObject("coach_quota") ?: o.optJSONObject("quota"))
    }

    suspend fun sendCoachMessage(text: String): JSONObject = withContext(Dispatchers.IO) {
        postJson("/api/coach/messages", JSONObject().put("text", text), "POST")
    }

    /** 确认/丢弃教练生成的待确认卡片。 */
    suspend fun confirmCard(cardId: String, action: String = "save"): JSONObject =
        withContext(Dispatchers.IO) {
            postJson("/api/coach/confirm", JSONObject().put("card_id", cardId).put("action", action), "POST")
        }

    /** 新建记录。成功返回记录视图，失败抛出带中文说明的异常。 */
    suspend fun createRecord(
        type: String,
        at: String,
        value: Double?,
        unit: String,
        meta: Map<String, Any?>,
    ): RecordView = withContext(Dispatchers.IO) {
        val metaObj = JSONObject()
        for ((k, v) in meta) if (v != null) metaObj.put(k, v)
        val payload = JSONObject()
            .put("type", type)
            .put("at", at)
            .put("unit", unit)
            .put("meta", metaObj)
        if (value != null) payload.put("value", value)
        RecordView.from(postJson("/api/records", payload, "POST"))
    }

    suspend fun deleteRecord(id: Int): Boolean = withContext(Dispatchers.IO) {
        val conn = open("/api/records/$id", "DELETE")
        try {
            val code = conn.responseCode
            drain(conn)
            code in 200..299
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ 内部
    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(rawGet(resolve(path)))
    }

    private suspend fun postJson(path: String, body: JSONObject, method: String): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = open(path, method)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = readAll(conn)
            conn.disconnect()
            if (code !in 200..299) {
                throw IllegalStateException(explainError(code, text))
            }
            JSONObject(text)
        }

    private fun resolve(path: String): String {
        val base = baseUrl ?: throw IllegalStateException("还没连接到后端")
        return base + path
    }

    private fun rawGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return try {
            val code = conn.responseCode
            val text = readAll(conn)
            if (code !in 200..299) throw IllegalStateException(explainError(code, text))
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val conn = URL(resolve(path)).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return conn
    }

    private fun readAll(conn: HttpURLConnection): String {
        val stream = try {
            conn.inputStream
        } catch (e: Exception) {
            conn.errorStream ?: return ""
        }
        return BufferedReader(stream.reader(Charsets.UTF_8)).use { it.readText() }
    }

    private fun drain(conn: HttpURLConnection) {
        try {
            readAll(conn)
        } catch (_: Exception) {
            // 忽略：DELETE 的响应体不重要
        }
    }

    /** 把 HTTP 状态翻译成用户能看懂的中文，而不是抛一个 status code。 */
    private fun explainError(code: Int, body: String): String {
        val serverMessage = try {
            JSONObject(body).optString("message").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        val hint = when (code) {
            400 -> "请求参数有误"
            401, 403 -> "没有权限"
            404 -> "接口不存在"
            409 -> "数据冲突"
            429 -> "操作过于频繁"
            in 500..599 -> "后端出错了"
            else -> "请求失败"
        }
        return serverMessage ?: "$hint（HTTP $code）"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
