package com.glpmate.native_app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glpmate.native_app.data.AppViewModel
import com.glpmate.native_app.data.CoachCard
import com.glpmate.native_app.data.CoachMessage
import com.glpmate.native_app.data.Quota
import com.glpmate.native_app.data.UiState

/**
 * 助手页（原生版）：和教练对话，让它读取真实记录、帮着记一笔、解释趋势。
 *
 * 三条不能越过的线，代码里都标了注释：
 *   1. 只渲染文本 —— 模型回复里的 Markdown 由本文件内的渲染器**当纯文本**画出来，
 *      不解析 HTML、不执行任何东西、也不引第三方 Markdown 库。
 *   2. 剂量建议不在这里做 —— 后端有硬护栏（拒绝改剂量、让用户找医生、急症升级），
 *      那些回复就是普通助手消息，原样显示，前端**不能**替它"补一句"。
 *   3. 额度用尽只挡对话 —— 记录、图表、提醒照常，界面上必须把这句话说清楚。
 *
 * 布局：整页只有一个纵向滚动容器（消息列表），消息多了列表自己滚；
 * 顶部标题/额度、底部输入框都固定，键盘弹出时靠外层 imePadding 把输入框顶上来。
 */
@Composable
fun CoachScreen(state: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    // 进页面拉一次历史；不放在组合里直接调（不能在组合期调挂起函数）
    LaunchedEffect(Unit) { vm.loadCoachMessages() }

    val messages = state.coachMessages
    val quota = state.bootstrap?.quota
    val listState = rememberLazyListState()

    // 新消息到达 / 开始等待回复时滚到底部，让最新的一条露出来
    LaunchedEffect(messages.size, state.coachSending) {
        val last = messages.lastIndex + if (state.coachSending) 1 else 0
        if (last >= 0) listState.animateScrollToItem(last)
    }

    // 只有这一个纵向滚动祖先：消息列表。标题与输入框都在它外面。
    // 这里**刻意不用 imePadding()**：Activity 是默认的 adjustResize，键盘弹出时
    // 窗口本身已经缩小了，再加一层键盘高度的内边距就是双重计算——结果是
    // weight(1f) 的消息列表被挤成 0 高度，界面看起来像"输入框浮在一片黑上"。
    // 空状态卡片也因此看不见。
    // 键盘处理说明（踩过的坑，别改回去）：
    //   本 Activity 是 enableEdgeToEdge + adjustNothing，键盘弹出时**窗口保持全高不动**。
    //   所以既不能用 adjustResize（对 edge-to-edge 无效），也不能给整个 Column 加
    //   imePadding（那会把标题和消息列表一起顶出屏幕，表现为顶部一大片空白）。
    //   正确做法：只有底部输入栏自己吃掉 ime inset，消息列表高度不变。
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        CoachHeader(quota = quota)

        if (quota != null && quota.limit > 0 && quota.remaining == 0) {
            QuotaExhaustedCard()
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 10.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && !state.coachSending) {
                item(key = "empty") { CoachEmptyCard() }
            }
            items(items = messages, key = { it.id }) { message ->
                MessageRow(
                    message = message,
                    onConfirmCard = { cardId, action -> vm.confirmCoachCard(cardId, action) },
                )
            }
            if (state.coachSending) {
                item(key = "pending") { PendingRow() }
            }
        }

        Composer(
            enabled = !state.coachSending,
            sending = state.coachSending,
            onSend = { text ->
                if (text.isNotEmpty() && !state.coachSending) {
                    vm.sendCoachMessage(text)
                }
            },
        )
    }
}

// ================================================================ 头部

@Composable
private fun CoachHeader(quota: Quota?) {
    Column(
        Modifier
            .fillMaxWidth()
            // 状态栏 inset 要显式加：enableEdgeToEdge 之后没人替我们让位
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("教练", color = GlpColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text("免费 · 通过书写提问和记录", color = GlpColors.Text3, fontSize = 12.sp)
            }
        }
        if (quota != null) {
            Spacer(Modifier.height(8.dp))
            val exhausted = quota.limit > 0 && quota.remaining == 0
            Text(
                "今日教练额度 ${quota.used}/${quota.limit} · 还剩 ${quota.remaining} 条",
                // 用尽时换成警示色（剂量/琥珀），不是报错红：这是一个状态，不是故障
                color = if (exhausted) GlpColors.Dose else GlpColors.Text2,
                fontSize = 12.sp,
                fontWeight = if (exhausted) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

/**
 * 额度用尽说明。
 *
 * 措辞对齐后端 server.py 里那句「今天的教练额度已用完（used/limit），记录、图表和提醒都不受影响。」
 * —— 额度只挡对话，不影响记录/图表/提醒，这一点必须让用户看见，不能被当成"应用坏了"。
 */
@Composable
private fun QuotaExhaustedCard() {
    GlpCard(background = GlpColors.Dose.copy(alpha = 0.10f)) {
        Text(
            "额度已用完，记录、图表和提醒都不受影响",
            color = GlpColors.Dose,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "今天的教练对话次数已经用完。这是自托管版本，额度在「设置 → 模型与额度」里可以随时调整，不涉及付费。",
            color = GlpColors.Text2,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

// ================================================================ 消息

@Composable
private fun MessageRow(message: CoachMessage, onConfirmCard: (String, String) -> Unit) {
    val isUser = message.isUser
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            // 气泡最宽占 86%：留出左/右的余地，一眼能看出是谁说的
            Modifier.fillMaxWidth(0.86f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            if (isUser) {
                // 用户写的是纯文本，不做 Markdown 渲染（否则打字里的 * 会变成格式）
                Column(
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(GlpColors.Accent.copy(alpha = 0.16f))
                        .border(1.dp, GlpColors.Accent.copy(alpha = 0.34f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    Text(
                        message.text,
                        color = GlpColors.Text,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                }
            } else {
                AssistantBubble(message, onConfirmCard)
            }

            val time = messageTime(message)
            if (!isUser && time.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(time, color = GlpColors.Text3, fontSize = 10.5.sp)
            }
        }
    }
}

@Composable
private fun AssistantBubble(message: CoachMessage, onConfirmCard: (String, String) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    // 模型调用失败时后端会插一条 assistant 消息并打上 meta.error：
    // 它必须以"错误"的样子出现，不能被当成正常回答读
    val error = message.isError
    val bg = if (error) GlpColors.Food.copy(alpha = 0.12f) else GlpColors.SurfaceAlt
    val border = if (error) GlpColors.Food.copy(alpha = 0.45f) else GlpColors.Line

    Column(
        Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .padding(horizontal = 13.dp, vertical = 10.dp),
    ) {
        if (error) {
            Text(
                "模型调用失败",
                color = GlpColors.Food,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
        }

        val text = message.text
        if (text.isBlank()) {
            Text("（这条回复是空的）", color = GlpColors.Text3, fontSize = 12.sp)
        } else {
            // 解析结果按 (id, text) 缓存：重组（滚动、输入）不该反复解析同一段 Markdown
            val blocks = remember(message.id, text) { parseMarkdown(text) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                blocks.forEach { block -> MarkdownBlockView(block, error) }
            }
        }

        if (message.card != null) {
            Spacer(Modifier.height(10.dp))
            CoachCardView(message.card, onConfirmCard)
        }
    }
}

/** 等待回复时的三点占位气泡。 */
@Composable
private fun PendingRow() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(GlpColors.SurfaceAlt)
                .border(1.dp, GlpColors.Line, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val transition = rememberInfiniteTransition(label = "dots")
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1100, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "phase",
            )
            // 三个点轮流亮：不做"…"这种静态省略号，一眼能看出还在等
            repeat(3) { index ->
                val distance = (phase - index + 3f) % 3f
                val dotAlpha = if (distance < 1f) 1f - distance * 0.7f else 0.3f
                Box(
                    Modifier
                        .size(6.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(GlpColors.Text2),
                )
                if (index < 2) Spacer(Modifier.width(5.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text("正在思考…", color = GlpColors.Text3, fontSize = 12.sp)
        }
    }
}

// ================================================================ 待确认卡片

@Composable
private fun CoachCardView(card: CoachCard, onConfirmCard: (String, String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlpColors.Elevated)
            .border(1.dp, GlpColors.Line, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text("确认卡片", color = GlpColors.Text3, fontSize = 10.5.sp)
        Spacer(Modifier.height(4.dp))
        // title / summary 已是中文，后端生成，直接显示
        Text(
            card.title.ifBlank { cardKindLabel(card.kind) },
            color = GlpColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (card.summary.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(card.summary, color = GlpColors.Text2, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(10.dp))

        if (card.isPending) {
            // 保存/丢弃都要用户点：模型自己不许写进记录（这是设计上的确认环节）
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton("保存", GlpColors.Accent) { onConfirmCard(card.id, "save") }
                Spacer(Modifier.width(10.dp))
                PillButton("丢弃", GlpColors.Text2) { onConfirmCard(card.id, "discard") }
            }
        } else {
            Text(
                if (card.status == "added" || card.status == "saved") "已保存" else "已丢弃",
                color = GlpColors.Text3,
                fontSize = 12.sp,
            )
        }
    }
}

private fun cardKindLabel(kind: String): String = when (kind) {
    "meal_confirm" -> "这一餐"
    "record_confirm" -> "这条记录"
    else -> "待确认的记录"
}

// ================================================================ 输入框

@Composable
private fun Composer(
    enabled: Boolean,
    sending: Boolean,
    onSend: (String) -> Unit,
) {
    // 输入内容由 Composer 自己持有，**刻意不提升到 CoachScreen**。
    // 提升上去的话每敲一个字都会重组整个界面，包括 LazyColumn 里所有气泡的
    // Markdown 重解析——那是输入卡顿的根源，而且和键盘无关。
    var value by remember { mutableStateOf("") }
    val canSend = enabled && value.isNotBlank()

    Column(
        Modifier
            .fillMaxWidth()
            .background(GlpColors.Surface)
            .border(1.dp, GlpColors.Line)
            // 输入栏自己让位于键盘。Activity 是 adjustNothing + edge-to-edge，
            // 窗口不会因键盘缩小，所以 inset 必须显式加在**这一栏**上，
            // 而不是整屏——加在整屏会把标题和消息列表一起顶出视野。
            .imePadding()
            // 键盘收起时的底部手势区
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlpColors.SurfaceAlt)
                    .border(1.dp, GlpColors.Line, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        "写下你吃了什么，或想问的问题…",
                        color = GlpColors.Text3,
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    enabled = enabled,
                    // 不设 maxLines：多行时输入框自己长高（上限 5 行），再长就在框内滚
                    textStyle = TextStyle(color = GlpColors.Text, fontSize = 14.sp, lineHeight = 20.sp),
                    cursorBrush = SolidColor(GlpColors.Accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = {
                        // 键盘上的"发送"和按钮走同一条路径：发完清空输入框
                        if (canSend) {
                            onSend(value.trim())
                            value = ""
                        }
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(10.dp))
            SendButton(
                canSend = canSend,
                sending = sending,
                onClick = {
                    if (canSend) {
                        onSend(value.trim())
                        value = ""
                    }
                },
            )
        }
        if (sending) {
            Spacer(Modifier.height(6.dp))
            Text("正在等教练回复…", color = GlpColors.Text3, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SendButton(canSend: Boolean, sending: Boolean, onClick: () -> Unit) {
    val color = if (canSend) GlpColors.Accent else GlpColors.SurfaceAlt
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(color)
            .then(if (canSend) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (sending) "…" else "↑",
            color = if (canSend) Color(0xFF04222B) else GlpColors.Text3,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ================================================================ 空状态

@Composable
private fun CoachEmptyCard() {
    GlpCard {
        Text("还没有对话", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("教练能帮你做三件事：", color = GlpColors.Text2, fontSize = 12.5.sp)
        Spacer(Modifier.height(6.dp))
        listOf(
            "记录整理：把你随口写的一句变成一条真正的记录，确认后才会保存",
            "趋势解读：读你的体重、剂量、餐食和副作用，讲讲最近在往哪走",
            "常识科普：GLP-1 的常见反应、怎么吃、怎么补水这类通用知识",
        ).forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("•", color = GlpColors.Accent, fontSize = 12.5.sp)
                Spacer(Modifier.width(7.dp))
                Text(line, color = GlpColors.Text2, fontSize = 12.5.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        // 这条必须在空状态里说：它决定了用户对教练的预期
        HintLine("它不会替你决定用药——要不要改剂量、要不要停药，那要交给你的医生。", "⚠️")
        Spacer(Modifier.height(4.dp))
        Text(
            "试着写一句：「今天午餐吃了红烧鲫鱼和半碗米饭」",
            color = GlpColors.Text3,
            fontSize = 11.5.sp,
        )
    }
}

// ================================================================ Markdown（安全渲染）

/**
 * 极小的 Markdown 渲染器，只认助手回复里真正会出现的语法。
 *
 * 安全规则：所有内容都以**文本**进入 Compose（Text / AnnotatedString），
 * 这里不生成任何 HTML、不解析 HTML、不碰链接跳转，也没有任何求值。
 * 因此模型回复里就算带有标记，也只会被当成字面文字画出来。
 *
 * 已知的取舍（宁可少渲染，不可乱渲染）：
 *   - 不支持链接、图片、表格、引用块（后端提示词里也不要求模型用）
 *   - 未闭合的 ** 保持字面显示，不做"猜它想加粗"
 *   - ```card / ```json 围栏一律**不渲染原文**：卡片已经由后端抽走，剩下的
 *     只可能是解析失败的 JSON，把原始 JSON 打印给用户看是最糟的失败方式
 */
private const val MD_MAX_LEN = 20000
private const val MD_MAX_LINE = 2000

private val FENCE_RE = Regex("^\\s*(```+|~~~+)\\s*([\\w+#.-]*)\\s*$")
private val HEADING_RE = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
private val BULLET_RE = Regex("^\\s*[-*+]\\s+(.*)$")
private val ORDERED_RE = Regex("^\\s*\\d+[.)]\\s+(.*)$")
private val ORDERED_NUM_RE = Regex("^\\s*(\\d+)[.)]\\s+")
private val JSON_LIKE_RE = Regex("^\\s*\\{\\s*\"")
private val TIME_RE = Regex("(\\d{2}:\\d{2})")

private sealed interface MdBlock {
    /** 标题：level 1..6 */
    data class Heading(val level: Int, val text: String) : MdBlock

    /** 普通段落（逐行保留，段落内的换行就是视觉换行） */
    data class Paragraph(val text: String) : MdBlock

    /** 列表项：bullet 为真用 •，否则用序号文本 */
    data class ListItem(val bullet: Boolean, val marker: String, val text: String) : MdBlock

    /** 围栏代码块 */
    data class Code(val text: String) : MdBlock
}

private fun parseMarkdown(raw: String): List<MdBlock> {
    var source = raw.replace("\r\n", "\n").replace('\r', '\n')
    if (source.length > MD_MAX_LEN) source = source.take(MD_MAX_LEN) + "…"

    val out = mutableListOf<MdBlock>()
    val lines = source.split('\n')
    var i = 0
    while (i < lines.size) {
        val current = lines[i]

        if (current.isBlank()) {
            i += 1
            continue
        }

        // ---- 围栏代码块 ----
        val fence = FENCE_RE.find(current)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val info = fence.groupValues[2].lowercase()
            val body = mutableListOf<String>()
            i += 1
            while (i < lines.size && lines[i].trim() != marker) {
                body.pushClipped(lines[i])
                i += 1
            }
            if (i < lines.size) i += 1 // 跳过闭合围栏（没闭合就到文末）
            val text = body.joinToString("\n")

            // 卡片围栏：后端已经把 ```card 抽成 message.card 并从正文里删掉了。
            // 这里再看到就说明它没能解析成卡片，于是整块丢掉——绝不把原始 JSON 打给用户。
            val cardLike = info == "card" || info == "json" ||
                (info.isEmpty() && JSON_LIKE_RE.containsMatchIn(text))
            if (!cardLike) out.add(MdBlock.Code(text))
            continue
        }

        // ---- 标题 ----
        val heading = HEADING_RE.find(current)
        if (heading != null) {
            out.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
            i += 1
            continue
        }

        // ---- 列表项（无序 / 有序各占一行，缩进按同级处理）----
        val bullet = BULLET_RE.find(current)
        if (bullet != null) {
            out.add(MdBlock.ListItem(true, "•", bullet.groupValues[1].trim()))
            i += 1
            continue
        }
        val ordered = ORDERED_RE.find(current)
        if (ordered != null) {
            val n = ORDERED_NUM_RE.find(current)?.groupValues?.get(1) ?: "1"
            out.add(MdBlock.ListItem(false, "$n.", ordered.groupValues[1].trim()))
            i += 1
            continue
        }

        // ---- 普通行 ----
        out.add(MdBlock.Paragraph(current.trim()))
        i += 1
    }
    return out
}

/** 超长单行先截断：不这么做的的话一整行 10 万字符的文本会让文本布局卡住界面。 */
private fun MutableList<String>.pushClipped(line: String) {
    add(if (line.length > MD_MAX_LINE) line.take(MD_MAX_LINE) + "…" else line)
}

@Composable
private fun MarkdownBlockView(block: MdBlock, error: Boolean) {
    // 出错的那条也用同样的排版，只是颜色偏红，读起来仍然清楚
    val bodyColor = if (error) GlpColors.Text2 else GlpColors.Text
    when (block) {
        is MdBlock.Heading -> Text(
            renderInline(block.text, bodyColor),
            fontSize = when (block.level) {
                1 -> 18.sp
                2 -> 16.5.sp
                3 -> 15.5.sp
                else -> 14.5.sp
            },
            fontWeight = FontWeight.SemiBold,
            lineHeight = 24.sp,
        )

        is MdBlock.Paragraph -> Text(
            renderInline(block.text, bodyColor),
            color = bodyColor,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )

        is MdBlock.ListItem -> Row(Modifier.fillMaxWidth()) {
            Text(
                block.marker,
                color = if (error) GlpColors.Text3 else GlpColors.Accent,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                modifier = Modifier.width(if (block.bullet) 14.dp else 22.dp),
            )
            Text(
                renderInline(block.text, bodyColor),
                color = bodyColor,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }

        is MdBlock.Code -> Text(
            block.text,
            color = GlpColors.Text2,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                // 比气泡更深一档：代码块要能一眼看出是"另一类内容"
                .background(GlpColors.Bg)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/**
 * 行内 Markdown → AnnotatedString。
 *
 * 支持 **粗体**、`行内代码`、~~删除线~~，以及反斜杠转义。
 * 嵌套的粗体（如 ****A**B**）按内层优先解析，结果合并成同一种样式，
 * 所以最坏情况也只是"粗体套粗体"，不会崩、不会把星号漏得到处都是。
 */
private fun renderInline(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    // 只设颜色：字号/行高由外层 Text 统一给（SpanStyle 没有 lineHeight 参数，
    // 在每个 span 上重复字号反而会让粗体/代码的字号不一致）
    pushStyle(SpanStyle(color = baseColor))

    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.setLength(0)
        }
    }

    var i = 0
    while (i < text.length) {
        val ch = text[i]

        // 反斜杠转义：\* 就是字面的 *
        if (ch == '\\' && i + 1 < text.length && "\\`*_{}[]()#+-.!~|".indexOf(text[i + 1]) >= 0) {
            plain.append(text[i + 1])
            i += 2
            continue
        }

        // `行内代码`
        if (ch == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                flush()
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        background = GlpColors.Bg,
                    )
                )
                append(text.substring(i + 1, end).trim().ifEmpty { " " })
                pop()
                i = end + 1
                continue
            }
        }

        // ~~删除线~~
        if (ch == '~' && text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                flush()
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(text.substring(i + 2, end))
                pop()
                i = end + 2
                continue
            }
        }

        // **粗体** / __粗体__
        if ((ch == '*' || ch == '_') && text.startsWith("$ch$ch", i)) {
            val end = text.indexOf("$ch$ch", i + 2)
            if (end > i + 2 && text.substring(i + 2, end).isNotBlank()) {
                flush()
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(renderInlineInner(text.substring(i + 2, end)))
                pop()
                i = end + 2
                continue
            }
        }

        plain.append(ch)
        i += 1
    }
    flush()
    pop()
}

/** 粗体内部再解析一层行内代码/删除线；粗体已经生效，所以这里不再递归粗体。 */
private fun renderInlineInner(text: String): AnnotatedString = buildAnnotatedString {
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.setLength(0)
        }
    }
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                flush()
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        background = GlpColors.Bg,
                    )
                )
                append(text.substring(i + 1, end).trim().ifEmpty { " " })
                pop()
                i = end + 1
                continue
            }
        }
        if (ch == '~' && text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                flush()
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(text.substring(i + 2, end))
                pop()
                i = end + 2
                continue
            }
        }
        plain.append(ch)
        i += 1
    }
    flush()
}

// ================================================================ 小工具

/** 气泡下方的时间：优先用后端给的 hh:mm，没有就从 ISO 时间串里取。 */
private fun messageTime(message: CoachMessage): String {
    if (message.time.isNotBlank()) return message.time
    return TIME_RE.find(message.at)?.groupValues?.get(1).orEmpty()
}
