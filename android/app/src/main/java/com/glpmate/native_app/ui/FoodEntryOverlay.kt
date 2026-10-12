package com.glpmate.native_app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glpmate.native_app.data.AppViewModel
import com.glpmate.native_app.data.Quota
import com.glpmate.native_app.data.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 饮食录入浮层（原生版）：一个入口，三种记录方式。
 *
 * 结构对着 RecordSheet.kt 的 RecordEntryOverlay 写：全屏覆盖层（不是 ModalBottomSheet，
 * 小屏上底部弹层会被键盘顶飞）、顶栏「取消 + 标题」、正文**只有一个 verticalScroll**、
 * 底部固定「主按钮 + 中文错误行」。调用方负责把它放在根 Box 的最后一个子节点。
 *
 * 三条路各自的网络行为（这是本文件最容易误导人的地方，改动前先看这里）：
 *
 *   A. 手动填写 —— `vm.addRecord("food", …)` → POST /api/records，一条记录，成功即入库。
 *   B. 文字描述 —— `vm.sendCoachMessage(text)` → POST /api/coach/messages，**只发消息**，
 *      真正入库要等教练在「助手」页回一张 meal_confirm 卡片、用户在那里确认。
 *      所以这里的按钮写「发给教练估算」，不写「保存」，提示语也直说"还没记上"。
 *   C. 拍照 / 相册 —— 本文件内的 HttpURLConnection 把 JPEG 以 multipart/form-data
 *      发到 POST /api/coach/photo（BackendClient 没有 multipart 方法，也不去改它）。
 *      后端**成功时会顺手建一张 meal_confirm 卡片**（server.py coach_photo），
 *      所以这里保存后必须提醒用户去助手页丢弃那张卡片，否则同一餐会记两遍。
 *      视觉模型不可用时后端返回 {"error":"vision_unavailable","message":"<中文原因>"}：
 *      原样显示该原因 + 重试 / 改用手动填写，**绝不自己编一个估算出来**。
 *
 * 三条硬约束：
 *
 * 1) **校验在调后端之前**。buildManualPayload() / buildPhotoPayload() 返回 null 时调用方
 *    什么都不做，非法输入（空描述、非数字、越界）永远到不了后端。
 * 2) **五个营养键永远都发**（kcal / protein_g / carb_g / fat_g / fiber_g），留空发 0.0。
 *    后端 daily_totals() 按这些键聚合，少发一个键不会报错，只会静默少算。
 *    "没填"和"填了 0"在库里长得一样是有意为之（与 RecordSheet 的餐食表单口径一致）；
 *    但也因此**不允许替用户编任何非 0 的默认值**。
 * 3) **纵向只有一个 verticalScroll**（覆盖层正文）。图片列表、餐次 chips 都是普通 Column/Row。
 */

// ================================================================ 入口

/**
 * 打开饮食录入浮层。
 *
 * @param initialMode 打开时默认哪条路："manual"（手动填写）| "text"（交给教练估算）| "photo"（拍照/相册）。
 *                    认不出的值一律退化成 manual。
 */
@Composable
fun FoodEntryOverlay(
    state: UiState,
    vm: AppViewModel,
    initialMode: String = "manual",
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 表单状态只建一次：浮层的生命周期就是"选一种方式 → 填完/拍完 → 关掉"。
    val st = remember(initialMode) { FoodFormState(initialMode) }

    // 图片处理 + 上传 + 解析：拍照、相册、失败重试三条路都汇到这里，
    // 保证只有一处解析逻辑、只有一处中文错误出口。
    val runEstimate: (PhotoSource) -> Unit = { source ->
        st.photo = PhotoStage.Uploading
        st.error = null
        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) {
                    when (source) {
                        is PhotoSource.Shot -> bitmapToJpeg(source.bitmap)
                        is PhotoSource.Picked -> uriToJpeg(context, source.uri)
                        is PhotoSource.Reuse -> source.jpeg
                    }
                }
                st.lastJpeg = jpeg
                // baseUrl 在连接成功前是 null：这里给中文原因，而不是抛一个 NPE/英文异常
                val base = vm.backend.baseUrl ?: state.baseUrl
                    ?: throw IllegalStateException("还没连上内嵌后端，暂时不能识别照片；请等连接成功后重试。")
                val estimate = withContext(Dispatchers.IO) { requestPhotoEstimate(base, jpeg) }
                st.photo = PhotoStage.Done(estimate)
                st.photoDesc = defaultPhotoDesc(estimate)
            } catch (e: CancellationException) {
                throw e                    // 协程取消不是失败：既不吞掉，也不当成错误提示
            } catch (e: Exception) {
                st.photo = PhotoStage.Failed(
                    e.message?.takeIf { it.isNotBlank() } ?: "照片估算失败，请重试或改用手动填写。"
                )
            }
        }
    }

    // 拍照：用 TakePicturePreview 拿一张小预览图。**不能用 TakePicture** —— 那要 FileProvider
    // 和清单权限，本文件不允许改别的地方；营养估算用小图完全够。
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        // bitmap == null 表示用户在相机里按了返回：什么都不做，也不报错（这不是失败）
        if (bitmap != null) runEstimate(PhotoSource.Shot(bitmap))
    }

    // 相册：GetContent("image/*") 返回 Uri，解码/缩放/压缩都在 IO 线程做
    val pickFromGallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runEstimate(PhotoSource.Picked(uri))
    }

    /** A. 手动填写：校验在 buildManualPayload 里做完，返回 null 就到此为止。 */
    val saveManual: () -> Unit = {
        val data = buildManualPayload(st)
        if (data != null) {
            vm.addRecord(
                type = "food",
                at = vm.nowIso(),
                value = data.kcal,
                unit = "kcal",
                meta = data.meta,
                successMessage = "餐食已记录",
            )
            onDismiss()               // addRecord 是 fire-and-forget：失败原因由它自己弹提示
        }
    }

    /** B. 交给教练估算：只发消息，不入库。回执在「助手」页。 */
    val sendToCoach: () -> Unit = {
        st.error = null
        val text = st.text.trim()
        when {
            text.isEmpty() ->
                st.error = "请先描述这一餐（例如：中午吃了红烧鲫鱼配米饭和一份青菜）"
            text.length > TEXT_MAX ->
                st.error = "描述太长了，请控制在 $TEXT_MAX 字以内"
            else -> {
                vm.sendCoachMessage(text)
                onDismiss()
                // 诚实措辞：这一步没有保存任何记录，卡片在助手页等确认
                vm.showToast("已交给教练估算，去助手页确认")
            }
        }
    }

    /** C. 拍照：用识别出的合计发一条 food 记录（描述可以改）。 */
    val savePhoto: (PhotoEstimate) -> Unit = { estimate ->
        val payload = buildPhotoPayload(st, estimate)
        if (payload != null) {
            vm.addRecord(
                type = "food",
                at = vm.nowIso(),
                value = payload.kcal,
                unit = "kcal",
                meta = payload.meta,
                successMessage = "餐食已记录",
            )
            onDismiss()
        }
    }

    // 物理返回键 = 取消（浮层是全屏的，返回键落到别的页面会很突兀）
    BackHandler { onDismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .background(GlpColors.Bg)
            .imePadding()
            // 必须自己吃掉空白处的点击：Compose 的命中测试会继续发给被盖住的兄弟节点，
            // 不拦的话点在空白处会穿透到底下的按钮，甚至切走标签页。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { },
    ) {
        Column(Modifier.fillMaxSize()) {
            FoodTopBar(title = "饮食录入", onCancel = onDismiss)

            // 方式选择器固定在顶部：换方式不用先滚回去；上传中不许切走
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MODES.forEach { (key, label) ->
                    ChoiceChip(label, st.mode == key) {
                        if (st.mode != key && st.photo !is PhotoStage.Uploading) {
                            st.mode = key
                            st.error = null
                        }
                    }
                }
            }

            // 整页只有这一个竖向滚动容器
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(6.dp))
                when (st.mode) {
                    MODE_TEXT -> TextModeBody(st, state.bootstrap?.quota)
                    MODE_PHOTO -> PhotoModeBody(
                        st = st,
                        onTakePhoto = { takePicture.launch(null) },
                        onPickGallery = { pickFromGallery.launch("image/*") },
                        onRetry = {
                            val bytes = st.lastJpeg
                            if (bytes == null) {
                                st.photo = PhotoStage.Failed("没有可以重试的图片，请重新拍照或从相册选择。")
                            } else {
                                runEstimate(PhotoSource.Reuse(bytes))
                            }
                        },
                        onManual = {
                            st.mode = MODE_MANUAL
                            st.error = null
                        },
                    )
                    else -> ManualModeBody(st)
                }
                Spacer(Modifier.height(20.dp))
            }

            // 底部主按钮：文案/可用性/行为随方式和阶段变化，但永远只有一个主按钮
            val busy = state.busy || st.photo is PhotoStage.Uploading || state.coachSending
            var actionLabel: String? = null
            var actionHint: String? = null
            var action: () -> Unit = {}
            when (st.mode) {
                MODE_TEXT -> {
                    actionLabel = "发给教练估算"
                    action = sendToCoach
                }
                MODE_PHOTO -> when (val stage = st.photo) {
                    is PhotoStage.Uploading -> {
                        actionLabel = "正在估算…"
                        actionHint = "照片正在发给本机后端的图像模型，快的话几秒，慢的话十几秒。"
                    }
                    is PhotoStage.Done -> {
                        // 照片路径**刻意不在这里写记录**：后端 /api/coach/photo 成功时
                        // 已经顺手建了一张 meal_confirm 待确认卡片。如果这里再 save 一条，
                        // 用户去助手页确认那张卡时同一餐就被记两遍。
                        // 所以这里只负责"生成估算"，落库统一走助手页的确认卡片——
                        // 只有一条写入路径，从根上不可能重复。
                        actionLabel = "去助手页确认"
                        actionHint = "估算已生成。为避免重复记账，这一步不写库；" +
                            "请到助手页点确认，那条餐食才会记入。"
                        action = {
                            vm.showToast("估算已生成，去助手页确认后才会记入")
                            onDismiss()
                        }
                    }
                    is PhotoStage.Failed -> actionHint = "可以点上面的「重试」，或者改用手动填写。"
                    else -> actionHint = "先拍一张，或者从相册里选一张。"
                }
                else -> {
                    actionLabel = if (state.busy) "保存中…" else "保存"
                    action = saveManual
                }
            }

            FoodBottomBar(
                error = st.error,
                hint = actionHint,
                label = actionLabel,
                enabled = !busy,
                onClick = action,
            )
        }
    }
}

// ================================================================ 顶栏 / 底栏

@Composable
private fun FoodTopBar(title: String, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onCancel() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("取消", color = GlpColors.Text2, fontSize = 14.sp)
        }
        Text(
            title,
            color = GlpColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(64.dp))   // 与左侧「取消」等宽，标题才真正居中
    }
}

/**
 * 底部：中文错误行 + 说明 + 主按钮。
 *
 * 错误贴着按钮放，而不是塞进表单里：表单是滚动的，错误如果在最上面，
 * 用户点按钮时可能看不到（与 RecordSheet 的 OverlayBottomBar 同一个理由）。
 * 主按钮允许为 null：拍照那条路在"还没选图"和"估算失败"时没有可执行的主操作，
 * 这时只给一句说明，不摆一个点了没反应的灰按钮。
 */
@Composable
private fun FoodBottomBar(
    error: String?,
    hint: String?,
    label: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(GlpColors.Surface)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (!error.isNullOrBlank()) {
            Text(
                error,
                color = GlpColors.Food,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        if (!hint.isNullOrBlank()) {
            Text(
                hint,
                color = GlpColors.Text3,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        if (label != null) {
            PrimaryButton(text = label, onClick = onClick, enabled = enabled)
        }
    }
}

// ================================================================ 三条路的正文

/** A. 手动填写：餐次 + 描述 + 五个选填数字（全部留空也允许，留空按 0 发）。 */
@Composable
private fun ManualModeBody(st: FoodFormState) {
    GlpCard {
        FoodField(
            label = "吃了什么",
            value = st.desc,
            onValueChange = { st.desc = it; st.error = null },
            placeholder = "例如：红烧鲫鱼配米饭",
            multiline = true,
        )
        Spacer(Modifier.height(12.dp))
        Text("餐次", color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FOOD_SLOTS.forEach { (key, cn) ->
                ChoiceChip(cn, st.slot == key) { st.slot = key; st.error = null }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("营养估算（都可以留空，留空按 0 记）", color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FoodField(
                "热量", st.kcal, { st.kcal = it; st.error = null },
                Modifier.weight(1f), placeholder = "500", unit = "kcal",
                keyboardType = KeyboardType.Decimal,
            )
            FoodField(
                "蛋白质", st.protein, { st.protein = it; st.error = null },
                Modifier.weight(1f), placeholder = "30", unit = "g",
                keyboardType = KeyboardType.Decimal,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FoodField(
                "碳水", st.carb, { st.carb = it; st.error = null },
                Modifier.weight(1f), placeholder = "60", unit = "g",
                keyboardType = KeyboardType.Decimal,
            )
            FoodField(
                "脂肪", st.fat, { st.fat = it; st.error = null },
                Modifier.weight(1f), placeholder = "20", unit = "g",
                keyboardType = KeyboardType.Decimal,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FoodField(
                "纤维", st.fiber, { st.fiber = it; st.error = null },
                Modifier.weight(1f), placeholder = "8", unit = "g",
                keyboardType = KeyboardType.Decimal,
            )
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        HintLine("五个数字会一起发给后端（留空发 0），只填热量也可以；数字直接进今天页的营养进度。", "🍽")
    }
}

/** B. 文字描述，让教练估算：只发消息，不入库。 */
@Composable
private fun TextModeBody(st: FoodFormState, quota: Quota?) {
    GlpCard {
        Text("用一句话说清吃了什么", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "教练会把这句家常话拆成一顿饭的营养估算。",
            color = GlpColors.Text3,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        Spacer(Modifier.height(12.dp))
        FoodField(
            label = "这一餐",
            value = st.text,
            onValueChange = { st.text = it; st.error = null },
            placeholder = "例如：中午吃了红烧鲫鱼配米饭和一份青菜",
            multiline = true,
        )
        Spacer(Modifier.height(8.dp))
        HintLine(
            "这一步只发消息，不会直接记一条餐食：教练在「助手」页回你一张待确认卡片，" +
                "你在那里点确认之后才会写进今天。",
            "💬",
        )
        if (quota != null) {
            HintLine("今日教练额度：还剩 ${quota.remaining}/${quota.limit} 次。", "ⓘ")
        }
    }
}

/** C. 拍照 / 相册：未选图 → 上传中 → 识别结果 → 失败重试，四个阶段。 */
@Composable
private fun PhotoModeBody(
    st: FoodFormState,
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onRetry: () -> Unit,
    onManual: () -> Unit,
) {
    when (val stage = st.photo) {
        is PhotoStage.Uploading -> GlpCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = GlpColors.Accent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text("正在识别这张照片…", color = GlpColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "图片已经压到最长边 $MAX_EDGE 像素再上传，估算结果出来后还要你确认才会保存。",
                color = GlpColors.Text3,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        is PhotoStage.Done -> PhotoResultCard(st, stage.estimate)

        is PhotoStage.Failed -> GlpCard {
            // 后端的 {"error":"vision_unavailable","message":"…"} 里的中文原因原样显示，不加工
            ErrorState(message = stage.message, onRetry = onRetry)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PillButton("改用手动填写", GlpColors.Text2) { onManual() }
            }
        }

        else -> GlpCard {
            Text("给这一餐拍张照", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "拍照用的是系统相机的预览图（分辨率不高，但估算营养够用）；" +
                    "从相册选的图会先缩到最长边 $MAX_EDGE 像素、压成 JPEG 再上传。",
                color = GlpColors.Text3,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("拍照", GlpColors.Accent) { onTakePhoto() }
                PillButton("从相册选择", GlpColors.Accent) { onPickGallery() }
            }
            Spacer(Modifier.height(6.dp))
            HintLine(
                "照片只发到本机后端做估算；如果后端配置的是云端模型，图片会随那次请求发给对应的服务商。",
                "🔒",
            )
            HintLine(
                "识别完不会自动入库：要你在这里点「保存」，或者到「助手」页确认那张待确认卡片。",
                "🍽",
            )
        }
    }
}

/** 识别结果：逐项列出 + 合计行 + 置信度 + 后端给的原话 + 可改的描述 + 餐次。 */
@Composable
private fun PhotoResultCard(st: FoodFormState, estimate: PhotoEstimate) {
    GlpCard {
        Text("识别结果", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "置信度：${confidenceCn(estimate.confidence)} · 模型估算值，请核对后再保存",
            color = GlpColors.Text3,
            fontSize = 11.sp,
        )
        if (estimate.note.isNotBlank()) {
            HintLine(estimate.note, "ⓘ")     // 后端的 note 原样显示，不改写
        }

        Spacer(Modifier.height(4.dp))
        estimate.items.forEach { item ->
            ListRow(
                title = item.name,
                subtitle = item.qty.takeIf { it.isNotBlank() },
                rightTop = formatNum(item.kcal, 0) + " kcal",
                rightBottom = "蛋白 " + formatNum(item.proteinG, 1) + " g",
                accent = GlpColors.Food,
                iconText = "🍽",
            )
        }
        if (estimate.items.isEmpty()) {
            Text(
                "模型没有单独列出菜名，只给了合计。",
                color = GlpColors.Text3,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(GlpColors.Line))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TotalStat("热量", estimate.kcal, "kcal")
            TotalStat("蛋白质", estimate.proteinG, "g")
            TotalStat("碳水", estimate.carbG, "g")
            TotalStat("脂肪", estimate.fatG, "g")
            TotalStat("纤维", estimate.fiberG, "g")
        }

        Spacer(Modifier.height(14.dp))
        FoodField(
            label = "这一餐的描述（可以改）",
            value = st.photoDesc,
            onValueChange = { st.photoDesc = it; st.error = null },
            placeholder = "例如：红烧鲫鱼配米饭",
        )
        Spacer(Modifier.height(12.dp))
        Text("餐次", color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FOOD_SLOTS.forEach { (key, cn) ->
                ChoiceChip(cn, st.slot == key) { st.slot = key; st.error = null }
            }
        }

        Spacer(Modifier.height(10.dp))
        HintLine(
            "后端已经为这张照片在「助手」页建了一张待确认卡片。如果在这里保存，" +
                "请到助手页把那张卡片丢弃，免得同一餐记两遍。",
            "⚠️",
        )
        Spacer(Modifier.height(4.dp))
        PillButton("重新拍照 / 换一张", GlpColors.Text2) {
            // 丢弃这次估算（后端那张卡片仍在助手页，用户去那边丢弃）
            st.photo = PhotoStage.Idle
            st.lastJpeg = null
            st.photoDesc = ""
            st.error = null
        }
    }
}

// ================================================================ 输入控件

/**
 * 本地输入框（BasicTextField + 设计系统令牌，视觉与 RecordSheet 的 EntryField 一致）。
 *
 * 为什么不复用 RecordSheet 的 EntryField：它是 private，本文件不能改那个文件。
 * 这里只保留本浮层要用的能力（单行/多行 + 单位后缀），不放 hint —— 说明统一走 HintLine，
 * 免得两个浮层各自长出一套提示样式。
 */
@Composable
private fun FoodField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    unit: String? = null,
    multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) GlpColors.Accent.copy(alpha = 0.7f) else GlpColors.Line

    Column(modifier) {
        Text(label, color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GlpColors.SurfaceAlt)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp)
                .then(if (multiline) Modifier.heightIn(min = 52.dp) else Modifier),
            verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, color = GlpColors.Text3, fontSize = 14.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = !multiline,
                    textStyle = TextStyle(color = GlpColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(GlpColors.Accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            if (!unit.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(unit, color = GlpColors.Text3, fontSize = 12.sp)
            }
        }
    }
}

/** 合计行里的一列：标签 + 数值（+ 单位），与今天页的 MacroStat 同一视觉，但没有目标值。 */
@Composable
private fun RowScope.TotalStat(label: String, value: Double, unit: String) {
    Column(Modifier.weight(1f)) {
        Text(label, color = GlpColors.Text3, fontSize = 10.5.sp)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatNum(value, if (unit == "kcal") 0 else 1),
                color = GlpColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(2.dp))
            Text(unit, color = GlpColors.Text3, fontSize = 9.5.sp)
        }
    }
}

// ================================================================ 表单状态

/**
 * 表单状态。
 *
 * 与 RecordSheet 的 EntryState 同样是"一个 class + mutableStateOf"，但只装饮食录入要用的字段：
 * 三条路的状态互不干扰，但共用一个中文错误出口（st.error → 底部红字）。
 */
private class FoodFormState(initialMode: String) {
    /** 当前方式："manual" | "text" | "photo"。 */
    var mode by mutableStateOf(normalizeMode(initialMode))

    // ---- A. 手动填写
    /** 餐次：默认按当前钟点猜（与后端 _slot_now() 的窗口完全一致）。 */
    var slot by mutableStateOf(slotByHour())
    var desc by mutableStateOf("")
    var kcal by mutableStateOf("")
    var protein by mutableStateOf("")
    var carb by mutableStateOf("")
    var fat by mutableStateOf("")
    var fiber by mutableStateOf("")

    // ---- B. 交给教练估算
    var text by mutableStateOf("")

    // ---- C. 拍照 / 相册
    var photo by mutableStateOf<PhotoStage>(PhotoStage.Idle)
    var photoDesc by mutableStateOf("")

    /**
     * 最近一次压缩好的 JPEG：重试时直接复用，不重新解码/压缩。
     * 它不是界面状态（没有任何界面读它来渲染），所以用普通 var，省掉一次无意义的重组。
     */
    var lastJpeg: ByteArray? = null

    /** 校验/保存失败的中文原因；非空时底部显示，并且对应的动作不会调后端。 */
    var error by mutableStateOf<String?>(null)
}

/** 拍照那条路的四个阶段。 */
private sealed class PhotoStage {
    /** 还没选图 */
    object Idle : PhotoStage()

    /** 压缩/上传/等模型，界面上是一个转圈 */
    object Uploading : PhotoStage()

    /** 拿到估算，等用户核对后保存或丢弃 */
    class Done(val estimate: PhotoEstimate) : PhotoStage()

    /** 失败：message 一定是中文（后端原因原样透传，其余由本文件翻译） */
    class Failed(val message: String) : PhotoStage()
}

/** 图片来源：拍照预览图 / 相册 Uri / 重试时复用上次的 JPEG。 */
private sealed class PhotoSource {
    class Shot(val bitmap: Bitmap) : PhotoSource()
    class Picked(val uri: Uri) : PhotoSource()
    class Reuse(val jpeg: ByteArray) : PhotoSource()
}

// ================================================================ 识别结果模型

private class PhotoItem(
    val name: String,
    val qty: String,
    val kcal: Double,
    val proteinG: Double,
)

private class PhotoEstimate(
    val items: List<PhotoItem>,
    val kcal: Double,
    val proteinG: Double,
    val carbG: Double,
    val fatG: Double,
    val fiberG: Double,
    val confidence: String,
    val note: String,
)

// ================================================================ 常量

private const val MODE_MANUAL = "manual"
private const val MODE_TEXT = "text"
private const val MODE_PHOTO = "photo"

/** 方式选择器：(mode key, 中文标签)；key 与 initialMode 的取值一致。 */
private val MODES: List<Pair<String, String>> = listOf(
    MODE_MANUAL to "手动填写",
    MODE_TEXT to "让教练估算",
    MODE_PHOTO to "拍照识别",
)

/** 餐次 key 必须与后端一致（breakfast/lunch/dinner/snack），中文只用于显示。 */
private val FOOD_SLOTS: List<Pair<String, String>> = listOf(
    "breakfast" to "早餐",
    "lunch" to "午餐",
    "dinner" to "晚餐",
    "snack" to "加餐",
)

// 五个营养字段的上限：与 RecordSheet 的餐食表单同一口径，超出按手误拦下。
// 识别结果（模型给的数）也按这套上限做一次合理性检查——模型偶尔会给出荒唐的大数，
// 那会直接污染今天页的日均统计，宁可拦下来让用户改用手动填写。
private const val KCAL_MAX = 5000.0
private const val PROTEIN_MAX = 500.0
private const val CARB_MAX = 800.0
private const val FAT_MAX = 500.0
private const val FIBER_MAX = 200.0

private const val DESC_MAX = 200
private const val TEXT_MAX = 500

/** 图片压缩口径与后端 coach.VISION_EDGE / quality=85 一致（最长边 1024、JPEG 85）。 */
private const val MAX_EDGE = 1024
private const val JPEG_QUALITY = 85

// 后端调视觉模型自己的超时是 120s（coach.ChatClient.timeout）。客户端读超时给得更长，
// 免得我们在读到后端的中文原因之前先掐断连接，把"模型超时"变成"网络错误"。
private const val CONNECT_TIMEOUT_MS = 15_000
private const val VISION_TIMEOUT_MS = 150_000

// ================================================================ 校验 + payload

/** 校验通过后的手动记录：value 是热量，meta 是七件套。 */
private class FoodPayload(val kcal: Double, val meta: Map<String, Any?>)

/**
 * A. 手动填写的校验 + 组装。
 *
 * 任何一项不合法都会把中文字段级原因写进 st.error 并返回 null —— 调用方只在拿到
 * 非 null 时才调 vm.addRecord，所以非法输入永远到不了 POST /api/records。
 * 五个营养键**永远都发**：留空发 0.0（不编造非 0 的默认值）。
 */
private fun buildManualPayload(st: FoodFormState): FoodPayload? {
    st.error = null
    val desc = st.desc.trim()
    if (desc.isEmpty()) {
        st.error = "请填写吃了什么"
        return null
    }
    if (desc.length > DESC_MAX) {
        st.error = "描述太长了，请控制在 $DESC_MAX 字以内"
        return null
    }
    val kcal = optNum(st.kcal, "热量", KCAL_MAX) { st.error = it } ?: return null
    val protein = optNum(st.protein, "蛋白质", PROTEIN_MAX) { st.error = it } ?: return null
    val carb = optNum(st.carb, "碳水", CARB_MAX) { st.error = it } ?: return null
    val fat = optNum(st.fat, "脂肪", FAT_MAX) { st.error = it } ?: return null
    val fiber = optNum(st.fiber, "纤维", FIBER_MAX) { st.error = it } ?: return null
    return FoodPayload(
        kcal = kcal,
        meta = foodMeta(st.slot, desc, kcal, protein, carb, fat, fiber),
    )
}

/**
 * C. 拍照的校验 + 组装：把识别出的**合计**发成一条 food 记录，键与手动填写完全一致。
 *
 * 这里再查一遍范围不是多余：模型给的大数会直接进日均统计，宁可拦下来让人改用手动填写。
 * 注意 meta 里不放 items —— 逐项明细只在界面上展示，入库口径与手动填写保持一致。
 */
private fun buildPhotoPayload(st: FoodFormState, estimate: PhotoEstimate): FoodPayload? {
    st.error = null
    val desc = st.photoDesc.trim()
    if (desc.isEmpty()) {
        st.error = "请填写这一餐的描述"
        return null
    }
    if (desc.length > DESC_MAX) {
        st.error = "描述太长了，请控制在 $DESC_MAX 字以内"
        return null
    }
    val checks = listOf(
        Triple("热量", estimate.kcal, KCAL_MAX),
        Triple("蛋白质", estimate.proteinG, PROTEIN_MAX),
        Triple("碳水", estimate.carbG, CARB_MAX),
        Triple("脂肪", estimate.fatG, FAT_MAX),
        Triple("纤维", estimate.fiberG, FIBER_MAX),
    )
    for ((label, value, max) in checks) {
        if (value.isNaN() || value.isInfinite() || value < 0.0) {
            st.error = "$label 的估算值不正常（${formatNum(value)}），请改用手动填写核对"
            return null
        }
        if (value > max) {
            st.error = "$label 的估算值 ${formatNum(value)} 超出合理范围（上限 ${formatNum(max)}），请改用手动填写核对"
            return null
        }
    }
    return FoodPayload(
        kcal = estimate.kcal,
        meta = foodMeta(
            st.slot, desc, estimate.kcal,
            estimate.proteinG, estimate.carbG, estimate.fatG, estimate.fiberG,
        ),
    )
}

/**
 * 一条 food 记录的 meta。
 *
 * 键名与后端 models.normalize_payload() / daily_totals() 的口径一致：
 * slot 决定今天页按哪一餐显示，后五个键是营养聚合的唯一来源。少一个键不会报错，
 * 只会静默少算——所以这里固定七个键，一个都不省。
 */
private fun foodMeta(
    slot: String,
    desc: String,
    kcal: Double,
    protein: Double,
    carb: Double,
    fat: Double,
    fiber: Double,
): Map<String, Any?> = mapOf(
    "slot" to slot,
    "desc" to desc,
    "kcal" to kcal,
    "protein_g" to protein,
    "carb_g" to carb,
    "fat_g" to fat,
    "fiber_g" to fiber,
)

/** 选填数字：留空记 0（五个键必须都发），填了就必须是非负数字且在范围内。 */
private fun optNum(raw: String, label: String, max: Double, fail: (String) -> Unit): Double? {
    val text = raw.trim()
    if (text.isEmpty()) return 0.0
    val n = text.toDoubleOrNull()
    if (n == null || n.isNaN() || n.isInfinite()) {
        fail("$label 请输入数字，留空按 0 记")
        return null
    }
    if (n < 0.0 || n > max) {
        fail("$label 需在 0–${formatNum(max)} 之间")
        return null
    }
    return n
}

/** 只认三个已知的方式；认不出的（包括以后改过名字的）一律退化成手动填写。 */
private fun normalizeMode(raw: String): String = when (raw.trim().lowercase()) {
    MODE_TEXT -> MODE_TEXT
    MODE_PHOTO -> MODE_PHOTO
    else -> MODE_MANUAL
}

/**
 * 餐次默认值：与后端 _slot_now()（server.py）逐字一致 ——
 * 10 点前早餐、15 点前午餐、21 点前晚餐，其余加餐。
 */
private fun slotByHour(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 10 -> "breakfast"
        hour < 15 -> "lunch"
        hour < 21 -> "dinner"
        else -> "snack"
    }
}

/** low/medium/high → 低/中/高；后端给了别的值就老实写"未知"，不猜。 */
private fun confidenceCn(raw: String): String = when (raw.trim().lowercase()) {
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    else -> "未知"
}

/** 描述默认值：把菜名串起来（与后端建卡片时的写法一致），一个菜名都没有才写"一餐"。 */
private fun defaultPhotoDesc(estimate: PhotoEstimate): String =
    estimate.items.joinToString("、") { it.name }.ifBlank { "一餐" }

// ================================================================ 图片处理

/** 拍照预览图 → JPEG 字节。 */
private fun bitmapToJpeg(bitmap: Bitmap): ByteArray =
    compressJpeg(shrinkToMaxEdge(bitmap, MAX_EDGE))

/**
 * 相册 Uri → 解码 → 缩到最长边 ≤ MAX_EDGE → JPEG 字节。
 *
 * 先只读图片头（inJustDecodeBounds）算出采样率再真正解码：直接 decodeStream 一张
 * 5000 万像素的照片会 OOM。整个过程只在 Dispatchers.IO 上跑。
 */
private fun uriToJpeg(context: Context, uri: Uri): ByteArray {
    val resolver = context.contentResolver
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalStateException("打不开这张图片，请换一张或改用手动填写。")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("这张图片没法解码（可能是不支持的格式），请换一张或改用手动填写。")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("打不开这张图片，请换一张或改用手动填写。")
        return compressJpeg(shrinkToMaxEdge(decoded, MAX_EDGE))
    } catch (e: SecurityException) {
        // 相册 Uri 的读权限只在当次选择有效：进程被杀后重开会走到这里
        throw IllegalStateException("系统没给这张图片的读取权限，请重新选择或改用手动填写。")
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("这个图片来源读不了，请重新选择或改用手动填写。")
    }
}

/** 采样率取 2 的幂：保证解码出来的位图不超过 MAX_EDGE 的两倍，避免大图 OOM。 */
private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    var longest = max(width, height)
    while (maxEdge > 0 && longest / 2 >= maxEdge) {
        longest /= 2
        sample *= 2
    }
    return sample
}

/** 最长边缩到 maxEdge；本来就不超就原样返回（不复制位图）。 */
private fun shrinkToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
    val longest = max(src.width, src.height)
    if (longest <= maxEdge || longest <= 0) return src
    val ratio = maxEdge.toFloat() / longest.toFloat()
    val width = max(1, (src.width * ratio).roundToInt())
    val height = max(1, (src.height * ratio).roundToInt())
    val scaled = Bitmap.createScaledBitmap(src, width, height, true)
    if (scaled !== src) src.recycle()     // 缩放成功后原始大图不再有人引用，尽早还给系统
    return scaled
}

/** 压成 JPEG。compress() 返回 false 或抛异常都当成失败，给中文原因，不静默返回空字节。 */
private fun compressJpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
    val out = ByteArrayOutputStream()
    val ok = try {
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    } catch (e: Exception) {
        throw IllegalStateException("图片压缩失败：${e.message ?: "JPEG 编码出错"}，请重新拍照或改用手动填写。")
    }
    if (!ok) throw IllegalStateException("图片压缩失败，请重新拍照或改用手动填写。")
    val bytes = out.toByteArray()
    if (bytes.isEmpty()) throw IllegalStateException("图片压缩后是空的，请重新拍照或改用手动填写。")
    return bytes
}

// ================================================================ 照片估算的网络调用

/**
 * 把 JPEG 以 multipart/form-data POST 到 {base}/api/coach/photo（字段名 file）。
 *
 * 为什么在这个文件里手写 HTTP：BackendClient 没有 multipart 方法，而需求不允许改它。
 * 只用 java.net.HttpURLConnection + 手拼 boundary，不引 OkHttp。
 *
 * 失败一律抛 IllegalStateException，message 一定是中文：要么是后端给的原因（逐字），
 * 要么是本文件按状态码翻译的说明。绝不在失败时返回一个编出来的估算。
 */
private fun requestPhotoEstimate(baseUrl: String, jpeg: ByteArray): PhotoEstimate {
    val boundary = "----glpmate" + UUID.randomUUID().toString().replace("-", "")
    val body = multipartBody(boundary, "file", "meal.jpg", "image/jpeg", jpeg)
    val conn = URL(baseUrl + "/api/coach/photo").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = VISION_TIMEOUT_MS
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }

        val code = conn.responseCode
        val text = readResponse(conn)
        if (code !in 200..299) throw IllegalStateException(explainHttpError(code, text))

        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalStateException("后端返回的内容没法解析（HTTP $code），请重试或改用手动填写。")
        }
        val error = json.optString("error")
        if (error.isNotBlank()) {
            // vision_unavailable（没配视觉模型 / 模型调用失败）等：把后端的中文原因原样交给界面
            throw IllegalStateException(
                json.optString("message").takeIf { it.isNotBlank() }
                    ?: "照片估算失败（$error），请重试或改用手动填写。"
            )
        }
        val estimateJson = json.optJSONObject("estimate")
            ?: throw IllegalStateException("后端没有返回估算结果，请重试或改用手动填写。")
        return parseEstimate(estimateJson)
    } finally {
        conn.disconnect()
    }
}

/** 手拼 multipart 请求体：后端 server.py form_file() 只认一个名为 file 的文件字段。 */
private fun multipartBody(
    boundary: String,
    field: String,
    filename: String,
    mime: String,
    bytes: ByteArray,
): ByteArray {
    val head = (
        "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\n" +
            "Content-Type: $mime\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)
    val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream(head.size + bytes.size + tail.size)
    out.write(head)
    out.write(bytes)
    out.write(tail)
    return out.toByteArray()
}

/** 读响应体：4xx/5xx 时 inputStream 会抛异常，改读 errorStream（照 BackendClient 的写法）。 */
private fun readResponse(conn: HttpURLConnection): String {
    val stream = try {
        conn.inputStream
    } catch (e: Exception) {
        conn.errorStream ?: return ""
    }
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

/**
 * HTTP 状态码 → 中文说明。
 * 后端把"模型不可用"这类业务失败也包成 HTTP 200 + error 字段，所以这里只处理真正的 HTTP 失败。
 */
private fun explainHttpError(code: Int, body: String): String {
    val serverMessage = try {
        JSONObject(body).optString("message").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
    val hint = when (code) {
        400 -> "后端说这张图片有问题"
        401, 403 -> "后端要求访问口令，照片没能上传"
        404 -> "后端没有照片估算这个接口"
        413 -> "图片太大了"
        429 -> "操作太频繁"
        in 500..599 -> "后端处理照片时出错了"
        else -> "照片上传失败"
    }
    return serverMessage ?: "$hint（HTTP $code），请重试或改用手动填写。"
}

/**
 * 解析 estimate。
 *
 * total 缺失、或"没有任何菜且合计热量为 0"都当失败：那种结果存进去只是一条空记录，
 * 让用户重试或手动填写比让他存一条 0 kcal 更诚实。
 */
private fun parseEstimate(o: JSONObject): PhotoEstimate {
    val items = mutableListOf<PhotoItem>()
    val arr = o.optJSONArray("items")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            items.add(
                PhotoItem(
                    name = row.optString("name").ifBlank { "食物" },
                    qty = row.optString("qty"),
                    kcal = row.optDouble("kcal", 0.0),
                    proteinG = row.optDouble("protein_g", 0.0),
                )
            )
        }
    }
    val total = o.optJSONObject("total")
        ?: throw IllegalStateException("后端返回的估算里没有合计，请重试或改用手动填写。")
    val estimate = PhotoEstimate(
        items = items,
        kcal = total.optDouble("kcal", 0.0),
        proteinG = total.optDouble("protein_g", 0.0),
        carbG = total.optDouble("carb_g", 0.0),
        fatG = total.optDouble("fat_g", 0.0),
        fiberG = total.optDouble("fiber_g", 0.0),
        confidence = o.optString("confidence"),
        note = o.optString("note"),
    )
    if (items.isEmpty() && estimate.kcal <= 0.0) {
        throw IllegalStateException("这次没有识别出任何食物，请换一张照片重试，或改用手动填写。")
    }
    return estimate
}
