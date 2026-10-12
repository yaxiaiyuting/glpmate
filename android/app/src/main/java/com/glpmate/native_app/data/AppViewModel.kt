package com.glpmate.native_app.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 界面状态。
 *
 * 只有一个 ViewModel：这个应用的界面之间共享同一份后端快照（今天页、数据页、
 * 图表都读同一批数据），拆成多个 ViewModel 只会带来同步问题。
 */
data class UiState(
    val connecting: Boolean = true,
    val connected: Boolean = false,
    val baseUrl: String? = null,
    val error: String? = null,
    val bootstrap: Bootstrap? = null,
    val busy: Boolean = false,
    val toast: String? = null,
    // 设置页需要的数据（进设置页时拉一次，避免每次重组都请求）
    val drugOptions: List<DrugOption> = emptyList(),
    val rawSettings: org.json.JSONObject? = null,
    // 助手页
    val coachMessages: List<CoachMessage> = emptyList(),
    val coachSending: Boolean = false,
)

class AppViewModel(private val client: BackendClient = BackendClient()) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val backend: BackendClient get() = client

    // 刻意不在 init 里自动连接：真机上后端是 APK 自己拉起来的（内嵌 CPython），
    // 必须等它起来再连。MainActivity 负责启动顺序。
    /** 后端启动失败时把原因显示出来，而不是让界面一直转圈。 */
    fun reportBackendFailure(reason: String) {
        _state.update {
            it.copy(
                connecting = false,
                connected = false,
                error = "内嵌后端启动失败：$reason",
            )
        }
    }

    fun connect() {
        _state.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            val result = client.connect()
            result.fold(
                onSuccess = { url ->
                    _state.update { it.copy(connecting = false, connected = true, baseUrl = url) }
                    refresh()
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            connecting = false,
                            connected = false,
                            error = "连不上后端：${error.message}\n" +
                                "真机上后端应该由 APK 自己拉起；如果你在用模拟器，" +
                                "请先在电脑上运行 python3 run.py。",
                        )
                    }
                },
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val data = client.bootstrap()
                _state.update { it.copy(bootstrap = data, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "刷新失败") }
            }
        }
    }

    /** 记一条记录；成功后刷新首页。失败把原因显示出来，不静默吞掉。 */
    fun addRecord(
        type: String,
        at: String,
        value: Double?,
        unit: String,
        meta: Map<String, Any?>,
        successMessage: String = "已记录",
    ) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                client.createRecord(type, at, value, unit, meta)
                _state.update { it.copy(busy = false, toast = successMessage) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, toast = "记录失败：${e.message}") }
            }
        }
    }

    fun deleteRecord(id: Int) {
        viewModelScope.launch {
            try {
                val ok = client.deleteRecord(id)
                _state.update { it.copy(toast = if (ok) "已删除" else "删除失败") }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(toast = "删除失败：${e.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- 助手页
    fun loadCoachMessages() {
        viewModelScope.launch {
            try {
                val (items, quota) = client.coachMessages()
                val messages = items.map { CoachMessage.from(it) }
                _state.update { it.copy(coachMessages = messages) }
                // 额度也要刷新到 bootstrap 里，否则助手页显示的额度会是旧的
                _state.value.bootstrap?.let { b ->
                    _state.update { it.copy(bootstrap = b.copy(quota = quota)) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(toast = "读取对话失败：${e.message}") }
            }
        }
    }

    /**
     * 发一条消息给教练。
     *
     * 注意后端的返回约定：**额度用尽、模型调用失败都会以 HTTP 200 + error 字段返回**，
     * 不是 HTTP 错误码。所以这里必须检查 error 字段，否则界面会把失败当成成功。
     */
    fun sendCoachMessage(text: String, onDone: () -> Unit = {}) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(coachSending = true) }
            try {
                val result = client.sendCoachMessage(text.trim())
                loadCoachMessages()
                val error = result.optString("error")
                val done = when {
                    error == "quota_exceeded" ->
                        result.optString("message").ifBlank { "今日额度已用完" }
                    error == "ai_error" ->
                        "模型调用失败：${result.optString("message")}"
                    error.isNotBlank() -> "发送失败：$error"
                    else -> null
                }
                _state.update { it.copy(coachSending = false, toast = done) }
            } catch (e: Exception) {
                _state.update { it.copy(coachSending = false, toast = "发送失败：${e.message}") }
            } finally {
                onDone()
            }
        }
    }

    /** 确认教练给出的待确认卡片（例如"把这一餐记下来"）。 */
    fun confirmCoachCard(cardId: String, action: String = "save") {
        viewModelScope.launch {
            try {
                val result = client.confirmCard(cardId, action)
                val ids = result.optJSONArray("record_ids")
                val count = ids?.length() ?: 0
                _state.update {
                    it.copy(toast = if (action == "save") "已保存 $count 条记录" else "已丢弃")
                }
                loadCoachMessages()
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(toast = "操作失败：${e.message}") }
            }
        }
    }

    /** 拉设置页要用的数据（药物列表 + 原始 settings/profile/targets）。 */
    fun loadSettingsData() {
        viewModelScope.launch {
            try {
                val options = client.drugOptions()
                val raw = client.settings()
                _state.update { it.copy(drugOptions = options, rawSettings = raw) }
            } catch (e: Exception) {
                _state.update { it.copy(toast = "读取设置失败：${e.message}") }
            }
        }
    }

    /**
     * 保存设置。profile / targets / schedule 都是可选片段，只传要改的部分。
     * 成功后刷新 bootstrap，让今天页立刻反映新剂量/目标。
     */
    fun saveSettings(
        profile: Map<String, Any?>? = null,
        targets: Map<String, Any?>? = null,
        schedule: List<Map<String, Any?>>? = null,
        successMessage: String = "已保存",
    ) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val body = org.json.JSONObject()
                profile?.let { map ->
                    val o = org.json.JSONObject()
                    for ((k, v) in map) if (v != null) o.put(k, v)
                    body.put("profile", o)
                }
                targets?.let { map ->
                    val o = org.json.JSONObject()
                    for ((k, v) in map) if (v != null) o.put(k, v)
                    body.put("targets", o)
                }
                schedule?.let { list ->
                    val arr = org.json.JSONArray()
                    for (item in list) {
                        val o = org.json.JSONObject()
                        for ((k, v) in item) if (v != null) o.put(k, v)
                        arr.put(o)
                    }
                    body.put("schedule", arr)
                }
                client.updateSettings(body)
                _state.update { it.copy(busy = false, toast = successMessage) }
                loadSettingsData()
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, toast = "保存失败：${e.message}") }
            }
        }
    }

    /**
     * 保存模型服务设置。
     *
     * apiKey 语义（对应后端的约定）：
     *   null  = 不改动（界面上没填就传 null，免得把已存的密钥清掉）
     *   ""    = 明确清除
     *   其他  = 设为新值
     */
    fun saveAiSettings(
        baseUrl: String,
        apiKey: String?,
        model: String,
        dailyQuota: Int,
        visionModel: String,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val payload = org.json.JSONObject()
                payload.put("ai_base_url", baseUrl.trim())
                payload.put("ai_model", model.trim())
                payload.put("daily_quota", dailyQuota)
                if (visionModel.isNotBlank()) payload.put("vision_model", visionModel.trim())
                when {
                    apiKey == null -> Unit                       // 不改动
                    apiKey.isEmpty() -> payload.put("ai_api_key", "")   // 明确清除
                    else -> payload.put("ai_api_key", apiKey.trim())
                }
                client.updateSettings(payload)
                _state.update { it.copy(busy = false, toast = "已保存模型设置") }
                loadSettingsData()
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, toast = "保存失败：${e.message}") }
            }
        }
    }

    /** 测试模型连接。结果通过 toast 反馈（成功报延迟，失败报后端给的中文原因）。 */
    fun testAiConnection(baseUrl: String, apiKey: String, model: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val overrides = mutableMapOf<String, String>()
                if (baseUrl.isNotBlank()) overrides["ai_base_url"] = baseUrl.trim()
                if (model.isNotBlank()) overrides["ai_model"] = model.trim()
                // 只有用户这次真的输入了新 key 才带上；否则用后端已存的
                if (apiKey.isNotBlank()) overrides["ai_api_key"] = apiKey.trim()
                val result = client.testAiConnection(overrides)
                val ok = result.optBoolean("ok", false)
                val message = if (ok) {
                    val latency = result.optInt("latency_ms", 0)
                    val usedModel = result.optString("model").ifBlank { model }
                    "连接成功：$usedModel（${latency}ms）"
                } else {
                    val endpoint = result.optString("endpoint")
                    val reason = result.optString("error").ifBlank { "未知原因" }
                    "连接失败：$reason" + if (endpoint.isNotBlank()) "\n实际请求地址：$endpoint" else ""
                }
                _state.update { it.copy(busy = false, toast = message) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, toast = "测试失败：${e.message}") }
            }
        }
    }

    /** 只提示、不改数据。用于"功能还没实现"这类诚实的反馈。 */
    fun showToast(message: String) {
        _state.update { it.copy(toast = message) }
    }

    fun clearToast() {
        _state.update { it.copy(toast = null) }
    }

    /** 便捷：当前时间的 ISO 本地时间字符串（后端接受这种格式）。 */
    fun nowIso(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        return fmt.format(java.util.Date())
    }
}
