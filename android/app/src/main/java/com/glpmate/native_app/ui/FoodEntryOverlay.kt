package com.glpmate.native_app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
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
 * 饮食录入浮层（原生版）：用户只回答"吃了什么"，营养数字全部由教练估。
 *
 * **这个文件里没有任何营养数字输入框**（热量 / 蛋白质 / 碳水 / 脂肪 / 纤维都没有）。
 * 用户能做的只有两件事：
 *
 *   A. 描述 —— 打一句家常话 + 选餐次，点「交给教练估算」。
 *   B. 拍照 —— 拍一张或从相册选一张，让视觉模型看。
 *
 * 两条路的网络行为（改动前先读这里，这是本文件最容易误导人的地方）：
 *
 *   A. `vm.sendCoachMessage("<餐次中文名>：<描述>")` → POST /api/coach/messages。
 *      **只发消息，不入库**。教练在「助手」页回一张 meal_confirm 待确认卡片，
 *      用户在那里点确认才会写成一条 food 记录。所以按钮写「交给教练估算」，
 *      绝不写「保存」；提示语也直说"这一步不会记任何东西"。
 *   B. 本文件内用 java.net.HttpURLConnection 把 JPEG 以 multipart/form-data
 *      发到 POST /api/coach/photo（字段名 file，文件名 meal.jpg）。后端成功时
 *      **自己会建一张 meal_confirm 卡片**（server.py coach_photo），所以这条路
 *      **同样不写库**——否则用户去助手页确认那张卡时，同一餐会被记两遍。
 *      视觉模型不可用时后端返回 {"error":"vision_unavailable","message":"<中文原因>"}：
 *      原样显示该原因 + 重试 / 改用文字描述，**绝不自己编一个估算出来**。
 *
 * 硬约束：
 *
 * 1) **校验在调后端之前**。描述为空 ⇒ 只在底部显示一行中文错误，一个请求都不发。
 * 2) **不写库**。本文件里没有 vm.addRecord，也没有 vm.nowIso：两条路都只把信息
 *    交给教练，落库统一在「助手」页的确认卡片上，只有一条写入路径，从根上不可能重复。
 * 3) **纵向只有一个 verticalScroll**（浮层正文）。图片、chips 都是普通 Row/Column。
 * 4) 结构对着 RecordSheet.kt 的 RecordEntryOverlay 写：全屏覆盖层（不是 ModalBottomSheet，
 *    小屏上底部弹层会被键盘顶飞）、顶栏「取消 + 标题」、正文一个滚动容器、
 *    底部固定「中文错误行 + 说明 + 主按钮」。调用方负责把它放在根 Box 的最后一个子节点。
 */

// ================================================================ 入口

/**
 * 打开饮食录入浮层。
 *
 * @param initialMode 打开时默认哪种方式："manual" / "text"（都是"描述"）| "photo"（拍照）。
 *                    认不出的值一律退化成"描述"（今天页的快捷入口会传 "photo"）。
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

    // 表单状态只建一次：浮层的生命周期就是"选一种方式 → 说清楚/拍一张 → 关掉"。
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
                st.lastJpeg = jpeg      // 重试直接复用，不重新解码/压缩
                // baseUrl 在连接成功前是 null：这里给中文原因，而不是抛一个 NPE/英文异常
                val base = vm.backend.baseUrl ?: state.baseUrl
                    ?: throw IllegalStateException(
                        "还没连上内嵌后端，暂时不能识别照片；请等连接成功后重试，或改用文字描述交给教练。"
                    )
                Log.i(TAG, "压缩后 ${jpeg.size} 字节，准备上传到 $base/api/coach/photo")
                val estimate = withContext(Dispatchers.IO) { requestPhotoEstimate(base, jpeg) }
                Log.i(
                    TAG,
                    "识别完成：${estimate.items.size} 项，合计 ${estimate.kcal} kcal，" +
                        "置信度=${estimate.confidence}",
                )
                st.photo = PhotoStage.Done(estimate)
            } catch (e: CancellationException) {
                throw e                    // 协程取消不是失败：既不吞掉，也不当成错误提示
            } catch (e: Exception) {
                Log.w(TAG, "照片估算失败：${e.javaClass.name}: ${e.message}", e)
                st.photo = PhotoStage.Failed(
                    e.message?.takeIf { it.isNotBlank() }
                        ?: "照片估算失败，请重试或改用文字描述交给教练。"
                )
            }
        }
    }

    // 拍照：用 TakePicturePreview 拿一张系统相机的预览图。**不能用 TakePicture** —— 那要 FileProvider。
    // 营养估算用小图完全够，而且不用往磁盘写文件、不用管临时文件的清理。
    //
    // 关于"点不开相机"：TakePicturePreview 走的是 android.media.action.IMAGE_CAPTURE，
    // Android 11+ 的包可见性会过滤掉未声明的 intent——manifest 里必须写
    // <queries><intent><action android:name="android.media.action.IMAGE_CAPTURE"/> ，
    // 否则 launch() 直接抛 ActivityNotFoundException，而且没有任何日志、只会"点了没反应"。
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        // bitmap == null 表示用户在相机里按了返回：什么都不做，也不报错（这不是失败）
        Log.i(TAG, "拍照返回：${if (bitmap == null) "null（用户取消）" else "${bitmap.width}x${bitmap.height}"}")
        if (bitmap != null) runEstimate(PhotoSource.Shot(bitmap))
    }

    /** 打开相机，并把"打不开"的原因如实告诉用户，而不是静默无反应。 */
    val openCamera: () -> Unit = {
        try {
            takePicture.launch(null)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "相机的 ActivityNotFoundException：这台设备没有能响应 IMAGE_CAPTURE 的应用", e)
            st.photo = PhotoStage.Failed(
                "这台设备上没有能响应拍照请求的相机应用。请改用「从相册选择」，或者改用文字描述交给教练估算。",
            )
        } catch (e: Exception) {
            Log.w(TAG, "打不开相机：${e.javaClass.name}", e)
            st.photo = PhotoStage.Failed(
                "打不开相机（${e.javaClass.simpleName}）。请改用「从相册选择」，或者改用文字描述交给教练估算。",
            )
        }
    }

    // 相册：GetContent("image/*") 返回 Uri，解码 / 缩放 / 压缩都在 IO 线程做
    val pickFromGallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // 这条路径上有三处可能失败（选择器打不开 / 返回 null / Uri 读不了），
        // 而它们的表现都是"没反应"。加日志让真凶自己暴露，同时把打不开变成中文提示。
        Log.i(TAG, "相册返回 uri=$uri")
        if (uri != null) {
            runEstimate(PhotoSource.Picked(uri))
        } else {
            Log.i(TAG, "相册没有返回图片（用户取消，或选择器没有回传数据）")
        }
    }

    /** 打开相册，并把"打不开"的原因如实告诉用户。 */
    val openGallery: () -> Unit = {
        try {
            pickFromGallery.launch("image/*")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "相册的 ActivityNotFoundException：这台设备没有能处理 ACTION_GET_CONTENT 的应用", e)
            st.photo = PhotoStage.Failed(
                "这台设备上没有能选图片的应用。请改用「拍照」，或者改用文字描述交给教练估算。",
            )
        } catch (e: Exception) {
            Log.w(TAG, "打不开相册：${e.javaClass.name}", e)
            st.photo = PhotoStage.Failed(
                "打不开相册（${e.javaClass.simpleName}）。请改用「拍照」，或者改用文字描述交给教练估算。",
            )
        }
    }

    /**
     * A. 交给教练估算：只发消息，不入库。
     *
     * 描述为空时**直接返回**，一行中文错误 + 一个请求都不发（buildPrompt 返回 null 就是这个意思）。
     * 消息文本带上餐次前缀（"午餐：红烧鲫鱼配米饭，还有一份青菜"），教练据此判断该记哪一餐。
     */
    val sendToCoach: () -> Unit = {
        val prompt = buildPrompt(st)
        if (prompt != null) {
            vm.sendCoachMessage(prompt)
            onDismiss()
            // 诚实措辞：这一步没有保存任何记录，卡片还在助手页等确认
            vm.showToast("已交给教练估算，去助手页确认后才会记入")
        }
        // prompt == null：buildPrompt 已经把中文原因写进 st.error，
        // 并且没有调用 sendCoachMessage —— 空描述永远不会到后端。
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

            // 方式选择器固定在顶部：换方式不用先滚回去；上传中不许切走（免得用户以为已经取消）
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
                    MODE_PHOTO -> PhotoModeBody(
                        st = st,
                        onTakePhoto = openCamera,        // 走带 try/catch 的版本，打不开也要有中文提示
                        onPickGallery = openGallery,
                        onRetry = {
                            val bytes = st.lastJpeg
                            if (bytes == null) {
                                st.photo = PhotoStage.Failed(
                                    "没有可以重试的图片，请重新拍照或从相册选择。"
                                )
                            } else {
                                runEstimate(PhotoSource.Reuse(bytes))
                            }
                        },
                        onDescribe = {
                            st.mode = MODE_DESCRIBE
                            st.error = null
                        },
                    )
                    else -> DescribeModeBody(st = st, quota = state.bootstrap?.quota)
                }
                Spacer(Modifier.height(20.dp))
            }

            // 底部主按钮：文案/可用性/行为随方式与阶段变化，但永远只有一个主按钮。
            // 拍照那条路在"还没选图"和"估算失败"时没有可执行的主操作，就给 null，
            // 只留一句说明，不摆一个点了没反应的灰按钮。
            val busy = state.busy || state.coachSending || st.photo is PhotoStage.Uploading
            var label: String? = null
            var hint: String? = null
            var action: () -> Unit = {}
            when (st.mode) {
                MODE_PHOTO -> when (val stage = st.photo) {
                    is PhotoStage.Uploading -> {
                        label = "正在估算…"
                        hint = "照片正在发给本机后端的图像模型，快的话几秒，慢的话十几秒。"
                    }
                    is PhotoStage.Done -> {
                        // 照片路径**刻意不在这里写记录**：后端 /api/coach/photo 成功时
                        // 已经建了一张 meal_confirm 待确认卡片。如果这里再写一条，
                        // 用户去助手页确认那张卡时同一餐就被记两遍。
                        // 所以这里只负责"把估算显示出来"，落库统一走助手页的确认卡片。
                        label = "去助手页确认"
                        hint = "估算已生成。为避免同一餐记两遍，这一步不写库；" +
                            "请到助手页点确认，那一餐才会记入。"
                        action = {
                            vm.showToast("估算已生成，去助手页确认后才会记入")
                            onDismiss()
                        }
                    }
                    is PhotoStage.Failed -> hint = "可以点上面的「重试」，或者改用文字描述交给教练估算。"
                    else -> hint = "先拍一张，或者从相册里选一张；也可以在顶部切到「描述」。"
                }
                else -> {
                    label = "交给教练估算"
                    action = sendToCoach
                }
            }
            FoodBottomBar(
                error = st.error,
                hint = hint,
                label = label,
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
            // 不透明背景是必需的：这行是固定的，下面的表单滚上来会透过去。
            .background(GlpColors.Bg)
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
 * 主按钮允许为 null：拍照那条路在没有可执行的主操作时只给一句说明。
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

// ================================================================ 两条路的正文

/** A. 描述：一句话 + 餐次。没有数字输入框——数字是教练的事。 */
@Composable
private fun DescribeModeBody(st: FoodFormState, quota: Quota?) {
    GlpCard {
        Text(
            "这一餐吃了什么",
            color = GlpColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "说句家常话就行：菜名、大概份量、做法提一下。热量和蛋白质这些数字全部由教练估，你不用填。",
            color = GlpColors.Text3,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        Spacer(Modifier.height(12.dp))
        DescField(
            label = "描述",
            value = st.desc,
            onValueChange = { st.desc = it; st.error = null },
            placeholder = "例如：中午吃了红烧鲫鱼配米饭，还有一份青菜",
        )
        Spacer(Modifier.height(12.dp))
        Text("餐次", color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FOOD_SLOTS.forEach { (key, cn) ->
                ChoiceChip(cn, st.slot == key) { st.slot = key; st.error = null }
            }
        }
        Spacer(Modifier.height(6.dp))
        // 常驻的诚实说明：这一步本身什么都不记
        HintLine(
            "这一步只会把描述发给教练，本身不记任何东西：教练在「助手」页回你一张待确认卡片，" +
                "你在那里点确认之后，这一餐才会写进今天。",
            "💬",
        )
        if (quota != null) {
            HintLine("今日教练额度：还剩 ${quota.remaining}/${quota.limit} 次。", "ⓘ")
        }
    }
}

/** B. 拍照 / 相册：未选图 → 上传中 → 识别结果 → 失败重试，四个阶段。 */
@Composable
private fun PhotoModeBody(
    st: FoodFormState,
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onRetry: () -> Unit,
    onDescribe: () -> Unit,
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
                Text(
                    "正在识别这张照片…",
                    color = GlpColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "图片已经缩到最长边 $MAX_EDGE 像素再上传。识别结果会以一张待确认卡片出现在「助手」页。",
                color = GlpColors.Text3,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        is PhotoStage.Done -> PhotoResultCard(
            estimate = stage.estimate,
            onReset = {
                // 只丢弃界面上这次结果；后端为它建的那张卡片仍在「助手」页，用户去那里丢弃
                st.photo = PhotoStage.Idle
                st.lastJpeg = null
                st.error = null
            },
        )

        is PhotoStage.Failed -> GlpCard {
            // 后端的 {"error":"vision_unavailable","message":"…"} 里的中文原因原样显示，不加工
            ErrorState(message = stage.message, onRetry = onRetry)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PillButton("改用文字描述", GlpColors.Text2) { onDescribe() }
            }
        }

        else -> GlpCard {
            Text("给这一餐拍张照", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "拍照用的是系统相机的预览图（分辨率不高，但估算营养够用）；" +
                    "从相册选的图会先缩到最长边 $MAX_EDGE 像素、压成 JPEG 再上传。" +
                    "数字同样由教练估，你只需要确认拍的是这一餐。",
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
                "识别完不会自动入库：后端会为这张照片在「助手」页放一张待确认卡片，" +
                    "你去那里点确认才会记入。",
                "🍽",
            )
        }
    }
}

/**
 * 识别结果：逐项列出 + 合计 + 置信度 + 后端给的原话。
 *
 * **这里没有可编辑的数字，也没有"保存"按钮**：数字是模型给的（原样显示，不四舍五入成好看的数），
 * 落库要等用户在「助手」页确认那张卡片。范围异常的估算只在界面上如实提醒，不改数、不编数。
 */
@Composable
private fun PhotoResultCard(estimate: PhotoEstimate, onReset: () -> Unit) {
    GlpCard {
        Text("识别结果", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "置信度：${confidenceCn(estimate.confidence)} · 模型估算值，请到助手页核对后再确认",
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

        val warning = sanityWarning(estimate)
        if (warning != null) {
            HintLine(warning, "⚠️")
        }

        Spacer(Modifier.height(6.dp))
        HintLine(
            "这张照片已经在「助手」页生成了一张待确认卡片。为避免同一餐记两遍，" +
                "这里不写库：请到助手页点确认，那一餐才会记入；不需要就在那里丢弃。",
            "⚠️",
        )
        Spacer(Modifier.height(4.dp))
        PillButton("重新拍照 / 换一张", GlpColors.Text2) { onReset() }
    }
}

// ================================================================ 输入控件

/**
 * 描述输入框（本地版）。
 *
 * 为什么不复用 RecordSheet 的 EntryField：它是 private，本文件不能改那个文件。
 * 这里只要多行文本一种形态——本浮层**没有任何数字输入框**，营养数字一律由教练估，
 * 说明文字统一走 HintLine，免得这里再长出一套提示样式。
 */
@Composable
private fun DescField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) GlpColors.Accent.copy(alpha = 0.7f) else GlpColors.Line

    Column {
        Text(label, color = GlpColors.Text2, fontSize = 11.5.sp)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GlpColors.SurfaceAlt)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp)
                .heightIn(min = 84.dp),
        ) {
            if (value.isEmpty()) {
                Text(placeholder, color = GlpColors.Text3, fontSize = 14.sp, lineHeight = 20.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(color = GlpColors.Text, fontSize = 14.sp, lineHeight = 20.sp),
                cursorBrush = SolidColor(GlpColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
            )
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
 * 表单状态：两条路各自的输入，共用一个中文错误出口（st.error → 底部红字）。
 *
 * 只有两个用户输入：描述文本 + 餐次。**没有任何营养数字字段**——这是本文件的核心约定，
 * 以后要加字段前先想清楚：数字该由教练估，不该由用户填。
 */
private class FoodFormState(initialMode: String) {
    /** 当前方式："describe" | "photo"。 */
    var mode by mutableStateOf(normalizeMode(initialMode))

    /** 餐次：默认按当前钟点猜（与后端 _slot_now() 的窗口一致）。 */
    var slot by mutableStateOf(slotByHour())

    /** 用户对"吃了什么"的描述。发给教练时会加上餐次前缀。 */
    var desc by mutableStateOf("")

    /** 拍照那条路的阶段。 */
    var photo by mutableStateOf<PhotoStage>(PhotoStage.Idle)

    /**
     * 最近一次压缩好的 JPEG：重试时直接复用，不重新解码/压缩。
     * 它不是界面状态（没有任何界面读它来渲染），所以用普通 var，省掉一次无意义的重组。
     */
    var lastJpeg: ByteArray? = null

    /** 校验失败的中文原因；非空时底部显示，并且不会调后端。 */
    var error by mutableStateOf<String?>(null)
}

/** 拍照那条路的四个阶段。 */
private sealed class PhotoStage {
    /** 还没选图 */
    object Idle : PhotoStage()

    /** 压缩/上传/等模型，界面上是一个转圈 */
    object Uploading : PhotoStage()

    /** 拿到估算，只展示；落库在「助手」页的确认卡片 */
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

private const val TAG = "GlpFood"

private const val MODE_DESCRIBE = "describe"
private const val MODE_PHOTO = "photo"

/** 方式选择器：(mode key, 中文标签)。用户在这里选的是"怎么描述这一餐"，不是"填哪些字段"。 */
private val MODES: List<Pair<String, String>> = listOf(
    MODE_DESCRIBE to "描述",
    MODE_PHOTO to "拍照",
)

/**
 * 餐次 key 必须与后端一致（breakfast/lunch/dinner/snack），中文只用于显示，
 * 也是发给教练的消息前缀（"午餐：…"）。
 */
private val FOOD_SLOTS: List<Pair<String, String>> = listOf(
    "breakfast" to "早餐",
    "lunch" to "午餐",
    "dinner" to "晚餐",
    "snack" to "加餐",
)

// 估算值的合理性上限：模型偶尔会给出荒唐的大数（例如 9000 kcal 的一餐）。
// 这里**只用来提醒**，不拦、不改、不替用户编数字：真正的落库决策在助手页的确认卡片上。
private const val KCAL_MAX = 5000.0
private const val PROTEIN_MAX = 500.0
private const val CARB_MAX = 800.0
private const val FAT_MAX = 500.0
private const val FIBER_MAX = 200.0

/** 描述长度上限：够写清一顿饭，又不至于把教练的额度浪费在一篇小作文上。 */
private const val DESC_MAX = 200

/** 图片压缩口径与后端 coach.VISION_EDGE / quality=85 一致（最长边 1024、JPEG 85）。 */
private const val MAX_EDGE = 1024
private const val JPEG_QUALITY = 85

// 后端调视觉模型自己的超时是 120s（coach.ChatClient.timeout）。客户端读超时给得更长，
// 免得我们在读到后端的中文原因之前先掐断连接，把"模型超时"变成"网络错误"。
private const val CONNECT_TIMEOUT_MS = 15_000
private const val VISION_TIMEOUT_MS = 150_000

// ================================================================ 校验 + 消息文本

/**
 * A. 把"餐次 + 描述"拼成发给教练的一句话，顺便做完校验。
 *
 * 返回 null 表示输入不合法：调用方什么都不做，所以空描述永远到不了 /api/coach/messages。
 * 这里只拼文本，**不生成任何营养数字**——那是教练的活。
 */
private fun buildPrompt(st: FoodFormState): String? {
    st.error = null
    val desc = st.desc.trim()
    if (desc.isEmpty()) {
        st.error = "请先说说这一餐吃了什么（例如：中午吃了红烧鲫鱼配米饭，还有一份青菜）"
        return null
    }
    if (desc.length > DESC_MAX) {
        st.error = "描述太长了，请控制在 $DESC_MAX 字以内"
        return null
    }
    // 前缀是中文餐次名：教练据此知道该记哪一餐，比再让用户选一次日期时间省事
    return "${slotLabelCn(st.slot)}：$desc"
}

/** 只认两种方式；"manual" / "text"（旧入口传的值）和一切认不出的值都退化成"描述"。 */
private fun normalizeMode(raw: String): String = when (raw.trim().lowercase()) {
    MODE_PHOTO -> MODE_PHOTO
    else -> MODE_DESCRIBE
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

/** 餐次 key → 中文名；只用于显示和消息前缀，认不出就写"这一餐"（不猜）。 */
private fun slotLabelCn(key: String): String =
    FOOD_SLOTS.firstOrNull { it.first == key }?.second ?: "这一餐"

/** low/medium/high → 低/中/高；后端给了别的值就老实写"未知"，不猜。 */
private fun confidenceCn(raw: String): String = when (raw.trim().lowercase()) {
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    else -> "未知"
}

/**
 * 估算值的合理性提醒。返回 null 表示看着正常。
 *
 * 只提醒，不改数：数字是模型给的，用户要看到的是原值；确认/丢弃的权力在助手页那张卡片上。
 */
private fun sanityWarning(estimate: PhotoEstimate): String? {
    val checks = listOf(
        Triple("热量", estimate.kcal, KCAL_MAX),
        Triple("蛋白质", estimate.proteinG, PROTEIN_MAX),
        Triple("碳水", estimate.carbG, CARB_MAX),
        Triple("脂肪", estimate.fatG, FAT_MAX),
        Triple("纤维", estimate.fiberG, FIBER_MAX),
    )
    for ((label, value, max) in checks) {
        if (value.isNaN() || value.isInfinite() || value < 0.0) {
            return "$label 的估算值不正常（${formatNum(value)}），请重试或换一张照片；" +
                "去助手页确认前也请再核对一遍。"
        }
        if (value > max) {
            return "$label 的估算值 ${formatNum(value)} 偏大（超过 ${formatNum(max)}），" +
                "确认前请先核对，必要时在助手页丢弃那张卡片。"
        }
    }
    return null
}

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
            ?: throw IllegalStateException("打不开这张图片，请换一张或改用文字描述交给教练。")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("这张图片没法解码（可能是不支持的格式），请换一张或改用文字描述交给教练。")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("打不开这张图片，请换一张或改用文字描述交给教练。")
        return compressJpeg(shrinkToMaxEdge(decoded, MAX_EDGE))
    } catch (e: SecurityException) {
        // 相册 Uri 的读权限只在当次选择有效：进程被杀后重开会走到这里
        throw IllegalStateException("系统没给这张图片的读取权限，请重新选择或改用文字描述交给教练。")
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("这个图片来源读不了，请重新选择或改用文字描述交给教练。")
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
        throw IllegalStateException("图片压缩失败：${e.message ?: "JPEG 编码出错"}，请重新拍照或改用文字描述交给教练。")
    }
    if (!ok) throw IllegalStateException("图片压缩失败，请重新拍照或改用文字描述交给教练。")
    val bytes = out.toByteArray()
    if (bytes.isEmpty()) throw IllegalStateException("图片压缩后是空的，请重新拍照或改用文字描述交给教练。")
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
 * 要么是本文件按状态码翻译的说明。**绝不在失败时返回一个编出来的估算**。
 */
private fun requestPhotoEstimate(baseUrl: String, jpeg: ByteArray): PhotoEstimate {
    val boundary = "----glpmate" + UUID.randomUUID().toString().replace("-", "")
    val body = multipartBody(boundary, "file", "meal.jpg", "image/jpeg", jpeg)
    val target = baseUrl + "/api/coach/photo"
    val conn = URL(target).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = VISION_TIMEOUT_MS
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setFixedLengthStreamingMode(body.size)
        Log.i(TAG, "POST $target（multipart 字段 file=meal.jpg，body ${body.size} 字节）")
        conn.outputStream.use { it.write(body) }

        val code = conn.responseCode
        val text = readResponse(conn)
        Log.i(TAG, "上传响应 HTTP $code：${text.take(200)}")
        if (code !in 200..299) throw IllegalStateException(explainHttpError(code, text))

        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalStateException("后端返回的内容没法解析（HTTP $code），请重试或改用文字描述交给教练。")
        }
        val error = json.optString("error")
        if (error.isNotBlank()) {
            // vision_unavailable（没配视觉模型 / 模型调用失败）等：把后端的中文原因原样交给界面
            throw IllegalStateException(
                json.optString("message").takeIf { it.isNotBlank() }
                    ?: "照片估算失败（$error），请重试或改用文字描述交给教练。"
            )
        }
        val estimateJson = json.optJSONObject("estimate")
            ?: throw IllegalStateException("后端没有返回估算结果，请重试或改用文字描述交给教练。")
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
    } catch (e: CancellationException) {
        throw e                       // 协程取消不是"读不到响应"，不能在这里被吞掉
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
    return serverMessage ?: "$hint（HTTP $code），请重试或改用文字描述交给教练。"
}

/**
 * 解析 estimate。
 *
 * total 缺失、或"没有任何菜且合计热量为 0"都当失败：那种结果没有任何信息量，
 * 让用户重试或改用文字描述比给他看一行 0 kcal 更诚实。**不补默认值、不猜菜品**。
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
        ?: throw IllegalStateException("后端返回的估算里没有合计，请重试或改用文字描述交给教练。")
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
        throw IllegalStateException("这次没有识别出任何食物，请换一张照片重试，或改用文字描述交给教练。")
    }
    return estimate
}
