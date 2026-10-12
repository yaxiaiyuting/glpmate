package com.glpmate.native_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glpmate.native_app.data.AppViewModel
import com.glpmate.native_app.data.LevelInfo
import com.glpmate.native_app.data.RecordView
import com.glpmate.native_app.data.UiState
import com.glpmate.native_app.data.WeightInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 数据页（原生版）。内容与 WebView 版 views/data.js 对齐：
 *   页头 → 本周概览 → 月历 → 当日明细 → 版块（7 个版块的详情浮层）
 *
 * 三条刻意的约束：
 *
 * 1. 所有趋势图都走 [TouchLineChart]（今天页用的同一个可触摸折线图）。
 *    这个应用里"横滑"是图表的专属手势；再写一个图表组件会让手势和配色各自漂移。
 *
 * 2. 删除一律"先确认、等后端确认消失、再刷新"。AppViewModel.deleteRecord 是
 *    "发了就不等"的（它在自己的协程里跑），如果点了删除就立刻重新拉日历/列表，
 *    两个请求会赛跑，界面会短暂显示"提示已删除但那一行还在"。
 *    这里用 [deleteAndAwait] 把时序钉死。
 *
 * 3. 每个网络调用都单独 try/catch 并把异常的中文 message 交给 ErrorState。
 *    一个接口挂了不能让整页空白，也不能静默吞掉。
 */
@Composable
fun DataScreen(
    state: UiState,
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    /** 浮层可见性变化时通知外层：底部标签栏在 AppRoot 里，子界面盖不住它，
     *  只能让外层在浮层打开时把标签栏藏起来。 */
    onOverlayChange: (Boolean) -> Unit = {},
) {
    val bootstrap = state.bootstrap
    // bootstrap 还没到时先用设备本地日期兜底：这个页面自己会去拉数据，
    // 没必要为了一个日期字段把整页卡在"正在加载"。
    val today = bootstrap?.today?.takeIf { it.length >= 10 } ?: localToday()
    val intervalDays = bootstrap?.profile?.intervalDays?.takeIf { it > 0 } ?: 7
    val doseExpected = (7.0 / intervalDays).roundToInt().coerceAtLeast(1)

    var selectedDay by remember { mutableStateOf<String?>(null) }
    var month by remember { mutableStateOf<String?>(null) }
    val calMonth = month ?: today.take(7)
    val dayKey = selectedDay ?: today

    var week by remember { mutableStateOf<WeekOverview?>(null) }
    var weekError by remember { mutableStateOf<String?>(null) }
    var weight by remember { mutableStateOf<WeightInfo?>(null) }
    var weightError by remember { mutableStateOf<String?>(null) }
    var doseWeek by remember { mutableStateOf<List<RecordView>>(emptyList()) }
    // 顶层"体内药物水平"概览用。原先只有点进「用药」版块才看得到曲线，
    // 顶层的统计卡片里完全没有这一项——对每天关心药效的人来说这是最该看到的数字。
    var topLevel by remember { mutableStateOf<LevelInfo?>(null) }
    var topLevelError by remember { mutableStateOf<String?>(null) }
    var doseError by remember { mutableStateOf<String?>(null) }

    var calendar by remember { mutableStateOf<JSONObject?>(null) }
    var calError by remember { mutableStateOf<String?>(null) }

    var dayRecords by remember { mutableStateOf<List<RecordView>?>(null) }
    var dayLoadedKey by remember { mutableStateOf<String?>(null) }
    var dayError by remember { mutableStateOf<String?>(null) }

    var reloadTick by remember { mutableStateOf(0) }
    var openSection by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<RecordView?>(null) }
    val scope = rememberCoroutineScope()

    // ---------------------------------------------------------------- 本周概览
    // summary 给蛋白质/饮水，weightChart 给体重与变化，records 给本周剂量次数。
    LaunchedEffect(today, reloadTick) {
        weekError = null
        weightError = null
        doseError = null
        val parsed = try {
            parseWeek(vm.backend.summary("week"))
        } catch (e: Exception) {
            weekError = e.message ?: "加载失败"
            null
        }
        week = parsed
        weight = try {
            vm.backend.weightChart(90)
        } catch (e: Exception) {
            weightError = e.message ?: "加载失败"
            null
        }
        val weekStart = parsed?.start?.takeIf { it.length >= 10 } ?: mondayOf(today)
        doseWeek = try {
            vm.backend.records(
                types = listOf("dose"),
                start = weekStart,
                end = addDays(weekStart, 6),
                limit = 200,
                order = "desc",
            )
        } catch (e: Exception) {
            doseError = e.message ?: "加载失败"
            emptyList()
        }
        topLevel = try {
            vm.backend.levelChart()
        } catch (e: Exception) {
            topLevelError = e.message ?: "加载失败"
            null
        }
    }

    // ---------------------------------------------------------------- 月历
    LaunchedEffect(calMonth, reloadTick) {
        calError = null
        calendar = null          // 换月先清空：旧月份的点阵不能挂在新标题下面
        try {
            calendar = vm.backend.calendar(calMonth)
        } catch (e: Exception) {
            calError = e.message ?: "加载失败"
        }
    }

    // ---------------------------------------------------------------- 当日明细
    LaunchedEffect(dayKey, reloadTick) {
        dayError = null
        try {
            val rows = vm.backend.records(start = dayKey, end = dayKey, limit = 300, order = "desc")
            dayRecords = rows
            dayLoadedKey = dayKey
        } catch (e: Exception) {
            dayError = e.message ?: "加载失败"
        }
    }

    val todayRows = dayRecords?.takeIf { dayLoadedKey == dayKey }

    Box(modifier = modifier.fillMaxSize()) {
        // 版块浮层打开时让外层隐藏底部标签栏。
    // 为什么需要这个回调：TabBar 在 AppRoot 里、本可组合之外，浮层再大也盖不住它。
    LaunchedEffect(openSection) { onOverlayChange(openSection != null) }

    Column(
            Modifier
                .fillMaxWidth()
                // 状态栏 inset 必须显式加：enableEdgeToEdge 之后内容会画到状态栏下面，
                // 导航是手写的、没有 Scaffold 替我们让位（与今天页同一处理）
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                Text("数据", color = GlpColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text("按天回看记录 · 月历与版块趋势", color = GlpColors.Text3, fontSize = 12.5.sp)
            }

            WeekOverviewCard(
                week = week,
                weekError = weekError,
                weight = weight,
                weightError = weightError,
                doseCount = doseWeek.count { it.meta["skipped"] != "true" },
                doseExpected = doseExpected,
                doseError = doseError,
                onRetry = { reloadTick += 1 },
            )

            Spacer(Modifier.height(12.dp))

            LevelOverviewCard(
                level = topLevel,
                error = topLevelError,
                onRetry = { reloadTick += 1 },
                onOpenDetail = { openSection = "dose" },
            )

            Spacer(Modifier.height(12.dp))

            CalendarCard(
                month = calMonth,
                data = calendar,
                error = calError,
                today = today,
                selectedDay = selectedDay,
                onPrev = { month = shiftMonth(calMonth, -1) },
                onNext = { month = shiftMonth(calMonth, 1) },
                onPick = { key -> selectedDay = if (selectedDay == key) null else key },
                onRetry = { reloadTick += 1 },
            )

            SectionHead(
                title = "${shortDate(dayKey)} ${weekdayCn(weekdayIndex(dayKey))}" +
                    if (dayKey == today) "（今天）" else "",
                trailing = if (selectedDay != null) "回到今天" else null,
                onTrailing = { selectedDay = null },
            )
            DayListCard(
                records = todayRows,
                error = dayError,
                busy = state.busy,
                onDelete = { pendingDelete = it },
                onRetry = { reloadTick += 1 },
            )

            SectionHead("版块")
            SectionsCard(onOpen = { openSection = it })

            Spacer(Modifier.height(96.dp))   // 与今天页一致：给底部导航/Snackbar 留空间
        }

        val active = SECTION_SPECS.firstOrNull { it.key == openSection }
        if (active != null) {
            SectionDetailOverlay(
                spec = active,
                today = today,
                vm = vm,
                waterTargetMl = week?.targetWaterMl ?: 2000.0,
                onClose = { openSection = null },
                onChanged = { reloadTick += 1 },
            )
        }
    }

    val target = pendingDelete
    if (target != null) {
        DeleteConfirmDialog(
            record = target,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    val fresh = deleteAndAwait(vm, target.id, null, dayKey, dayKey)
                    if (fresh != null) {
                        dayRecords = fresh
                        dayLoadedKey = dayKey
                    }
                    reloadTick += 1
                }
            },
        )
    }
}

// ==================================================================== 本周概览

@Composable
private fun WeekOverviewCard(
    week: WeekOverview?,
    weekError: String?,
    weight: WeightInfo?,
    weightError: String?,
    doseCount: Int,
    doseExpected: Int,
    doseError: String?,
    onRetry: () -> Unit,
) {
    GlpCard {
        CardTitle("本周概览", weekRangeText(week))
        Spacer(Modifier.height(2.dp))
        Text(
            week?.let { "统计截至 ${shortDate(it.end)} · 已统计 ${it.days} 天" }
                ?: if (weekError == null) "正在加载…" else "本周汇总没能加载",
            color = GlpColors.Text3,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(12.dp))

        if (week == null && weekError != null) {
            ErrorState(message = "本周汇总加载失败：$weekError", onRetry = onRetry)
        } else {
            val latest = weight?.latest ?: weight?.series?.lastOrNull()?.v
            val first = weight?.series?.firstOrNull()?.v ?: weight?.start
            val seriesSize = weight?.series?.size ?: 0
            val delta = weight?.delta ?: if (latest != null && first != null) latest - first else null
            val lastDate = weight?.series?.lastOrNull()?.t
            val weightHint = when {
                latest == null -> "还没有体重记录"
                delta != null && seriesSize > 1 ->
                    (if (delta > 0) "+" else "") + formatNum(delta, 1) + " kg 较起始"
                else -> "首次记录"
            } + if (lastDate != null) " · ${shortDate(lastDate)}" else ""

            val proteinPerDay = week?.proteinPerDay
            val proteinTarget = week?.targetProtein ?: 90.0
            val waterPerDayL = week?.let { it.waterPerDayMl / 1000.0 }
            val waterTargetMl = week?.targetWaterMl ?: 2000.0

            Row(Modifier.fillMaxWidth()) {
                StatTile(
                    label = "体重",
                    value = latest?.let { formatNum(it, 1) } ?: "—",
                    unit = "kg",
                    hint = weightHint,
                    modifier = Modifier.weight(1f),
                    valueColor = GlpColors.Weight,
                )
                Spacer(Modifier.width(8.dp))
                StatTile(
                    label = "平均蛋白质",
                    value = proteinPerDay?.let { formatNum(it, 0) } ?: "—",
                    unit = "g/天",
                    hint = "目标 ${formatNum(proteinTarget, 0)} g/天",
                    modifier = Modifier.weight(1f),
                    bar = proteinPerDay?.let { it to proteinTarget },
                )
                Spacer(Modifier.width(8.dp))
                StatTile(
                    label = "剂量次数",
                    value = doseCount.toString(),
                    unit = "次",
                    hint = "本周应记录 $doseExpected 次",
                    modifier = Modifier.weight(1f),
                    valueColor = GlpColors.Dose,
                    bar = doseCount.toDouble() to doseExpected.toDouble(),
                    barColor = GlpColors.Dose,
                )
                Spacer(Modifier.width(8.dp))
                StatTile(
                    label = "饮水",
                    value = waterPerDayL?.let { formatNum(it, 1) } ?: "—",
                    unit = "L/天",
                    hint = "目标 ${formatNum(waterTargetMl / 1000.0, 1)} L/天",
                    modifier = Modifier.weight(1f),
                    valueColor = GlpColors.Water,
                    bar = waterPerDayL?.let { it to waterTargetMl / 1000.0 },
                    barColor = GlpColors.Water,
                )
            }
        }

        // 局部失败也要说出来：宁可显示"体重没拉到"，也不能让用户以为这就是全部数据
        if (weekError != null && week != null) HintLine("本周汇总刷新失败：$weekError")
        if (weightError != null) HintLine("体重数据加载失败：$weightError")
        if (doseError != null) HintLine("剂量记录加载失败：$doseError")
        if ((weekError != null && week != null) || weightError != null || doseError != null) {
            Spacer(Modifier.height(8.dp))
            PillButton("重试", GlpColors.Food) { onRetry() }
        }
    }
}

/** 概览里的小格子：标签 / 数值+单位 / 说明 / 可选的进度条（对应 SPA 的 .tile）。 */
@Composable
private fun StatTile(
    label: String,
    value: String,
    unit: String?,
    hint: String,
    modifier: Modifier = Modifier,
    valueColor: Color = GlpColors.Text,
    bar: Pair<Double, Double>? = null,
    barColor: Color = GlpColors.Accent,
) {
    Column(modifier) {
        Text(label, color = GlpColors.Text3, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = valueColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (!unit.isNullOrBlank()) {
                Text(" $unit", color = GlpColors.Text2, fontSize = 10.5.sp)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(hint, color = GlpColors.Text3, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2)
        if (bar != null) {
            Spacer(Modifier.height(5.dp))
            ThinBar(bar.first, bar.second, barColor)
        }
    }
}

// ==================================================================== 月历

@Composable
private fun CalendarCard(
    month: String,
    data: JSONObject?,
    error: String?,
    today: String,
    selectedDay: String?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val days = remember(data) { parseCalendarDays(data) }
    val dotColors = remember(data) { data?.optJSONObject("dot_colors") }
    val dataMonth = data?.optString("month")?.takeIf { it.length == 7 } ?: month
    val title = if (data != null && data.optString("month_cn").isNotBlank()) {
        "${data.optString("month_cn")} ${data.optInt("year")}"
    } else {
        monthTitleFallback(month)
    }

    GlpCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonthNavButton("‹", onPrev)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("点日期筛选下面的记录", color = GlpColors.Text3, fontSize = 10.5.sp)
            }
            Spacer(Modifier.weight(1f))
            MonthNavButton("›", onNext)
        }
        Spacer(Modifier.height(12.dp))

        when {
            // "有没有数据"和"加载完没有"是两件事：整月没有任何记录时 days 也是空的，
            // 所以判据必须是 data 是否为 null，不能用 days.isEmpty()
            data == null && error != null -> ErrorState(message = "日历加载失败：$error", onRetry = onRetry)
            data == null -> Text("正在加载…", color = GlpColors.Text3, fontSize = 12.sp)
            else -> {
                val first = data?.optInt("first_weekday", 0) ?: 0
                val count = data?.optInt("days_in_month", 0) ?: 0
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                        Text(
                            label,
                            color = GlpColors.Text3,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                val cells = first + count
                var index = 0
                while (index < cells) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val cell = index + col
                            if (cell < first || cell >= cells) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                val dayNum = cell - first + 1
                                val key = String.format(Locale.US, "%s-%02d", dataMonth, dayNum)
                                DayCell(
                                    dayNum = dayNum,
                                    info = days[key],
                                    isToday = key == today,
                                    isSelected = key == selectedDay,
                                    dotColors = dotColors,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onPick(key) },
                                )
                            }
                        }
                    }
                    index += 7
                }
            }
        }

        if (error != null && data != null) HintLine("日历刷新失败：$error")
        Spacer(Modifier.height(6.dp))
        Legend(dotColors)
    }
}

@Composable
private fun MonthNavButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0x0FFFFFFF))
            .border(1.dp, GlpColors.Line, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = GlpColors.Text2, fontSize = 17.sp)
    }
}

@Composable
private fun DayCell(
    dayNum: Int,
    info: DayInfo?,
    isToday: Boolean,
    isSelected: Boolean,
    dotColors: JSONObject?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val planned = info?.planned == true
    Box(modifier.height(46.dp).padding(2.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        isSelected -> GlpColors.Accent.copy(alpha = 0.16f)
                        isToday -> GlpColors.Accent.copy(alpha = 0.08f)
                        else -> Color.Transparent
                    },
                )
                .then(
                    if (isSelected) Modifier.border(1.5.dp, GlpColors.Accent, RoundedCornerShape(12.dp))
                    else Modifier,
                )
                .drawBehind {
                    // 计划剂量日：虚线圈。用画的而不是 border，因为 border 画不出虚线
                    if (planned) {
                        drawCircle(
                            color = GlpColors.Dose.copy(alpha = 0.95f),
                            radius = size.minDimension / 2f - 2.5f,
                            style = Stroke(
                                width = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f)),
                            ),
                        )
                    }
                }
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    dayNum.toString(),
                    color = when {
                        isToday -> GlpColors.Accent
                        info != null -> GlpColors.Text2
                        else -> GlpColors.Text3
                    },
                    fontSize = 12.5.sp,
                    fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(Modifier.height(3.dp))
                // 固定占位：没有点的日子数字也要对齐
                Row(Modifier.height(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    val dots = info?.dots?.take(3).orEmpty()
                    dots.forEachIndexed { i, type ->
                        if (i > 0) Spacer(Modifier.width(2.dp))
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(dotColorFor(type, info, i, dotColors)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(dotColors: JSONObject?) {
    Column(Modifier.fillMaxWidth()) {
        LEGEND.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { (type, label) ->
                    Row(
                        Modifier.padding(end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(legendColor(type, dotColors)),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(label, color = GlpColors.Text3, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ==================================================================== 当日明细

@Composable
private fun DayListCard(
    records: List<RecordView>?,
    error: String?,
    busy: Boolean,
    onDelete: (RecordView) -> Unit,
    onRetry: () -> Unit,
) {
    GlpCard {
        CardTitle(
            "记录",
            when {
                error != null -> "加载失败"
                busy -> "同步中…"
                records == null -> "正在加载…"
                records.isEmpty() -> "暂无"
                else -> "共 ${records.size} 条 · 最新在前"
            },
        )
        Spacer(Modifier.height(4.dp))
        when {
            error != null -> ErrorState(message = "记录加载失败：$error", onRetry = onRetry)
            records == null -> Text("正在加载…", color = GlpColors.Text3, fontSize = 12.sp)
            records.isEmpty() -> EmptyState("这天还没有记录", "在日历上换一天，或回到今天")
            else -> records.forEach { record ->
                RecordRow(record = record, onDelete = { onDelete(record) })
            }
        }
    }
}

/** 一行记录 + 行内删除按钮（ListRow 没有操作位，所以在它右侧加一个紧凑按钮）。 */
@Composable
private fun RecordRow(record: RecordView, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            ListRow(
                title = record.title.ifBlank { record.type },
                subtitle = record.subtitle.ifBlank { null },
                rightTop = recordValueText(record),
                rightBottom = record.time.ifBlank { null },
                accent = colorForType(record.type),
                iconText = iconForType(record.type),
            )
        }
        Text(
            "删除",
            color = GlpColors.Food,
            fontSize = 11.5.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onDelete() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DeleteConfirmDialog(record: RecordView, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val whenText = listOf(record.date.takeIf { it.isNotBlank() }?.let { shortDate(it) }, record.time)
        .filterNotNull()
        .filter { it.isNotBlank() }
        .joinToString(" ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "删除这条记录？",
                color = GlpColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                "${record.title.ifBlank { "记录" }} · ${recordValueText(record)} · $whenText，删除后无法恢复。",
                color = GlpColors.Text2,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = GlpColors.Food, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = GlpColors.Text2, fontSize = 14.sp)
            }
        },
        containerColor = GlpColors.Elevated,
        titleContentColor = GlpColors.Text,
        textContentColor = GlpColors.Text2,
    )
}

// ==================================================================== 版块列表

@Composable
private fun SectionsCard(onOpen: (String) -> Unit) {
    GlpCard {
        SECTION_SPECS.forEachIndexed { index, spec ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(GlpColors.Line))
            }
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(spec.key) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    ListRow(
                        title = spec.label,
                        subtitle = spec.hint,
                        accent = colorForType(spec.types.first()),
                        iconText = iconForType(spec.types.first()),
                    )
                }
                Text("›", color = GlpColors.Text3, fontSize = 18.sp)
            }
        }
    }
}

// ==================================================================== 版块详情（全屏浮层）

@Composable
private fun SectionDetailOverlay(
    spec: SectionSpec,
    today: String,
    vm: AppViewModel,
    waterTargetMl: Double,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    // 系统返回键先关浮层，而不是退出应用
    BackHandler { onClose() }

    var days by remember(spec.key) { mutableStateOf(30) }
    var items by remember(spec.key) { mutableStateOf<List<RecordView>?>(null) }
    var listError by remember(spec.key) { mutableStateOf<String?>(null) }
    var chartError by remember(spec.key) { mutableStateOf<String?>(null) }
    var weight by remember(spec.key) { mutableStateOf<WeightInfo?>(null) }
    var level by remember(spec.key) { mutableStateOf<LevelInfo?>(null) }
    var summaryJson by remember(spec.key) { mutableStateOf<JSONObject?>(null) }
    var summaryError by remember(spec.key) { mutableStateOf<String?>(null) }
    var loading by remember(spec.key) { mutableStateOf(true) }
    var reload by remember(spec.key) { mutableStateOf(0) }
    var pendingDelete by remember(spec.key) { mutableStateOf<RecordView?>(null) }
    val scope = rememberCoroutineScope()

    val rangeStart = addDays(today, -(days - 1))
    val rangeEnd = today
    val rangeText = "近 $days 天 · ${shortDate(rangeStart)}–${shortDate(rangeEnd)}"

    // 换时间范围 / 删除后都重新拉：记录、图表来源（体重或体内水平）、饮食汇总
    LaunchedEffect(spec.key, days, reload) {
        loading = true
        listError = null
        chartError = null
        summaryError = null
        try {
            items = vm.backend.records(
                types = spec.types,
                start = rangeStart,
                end = rangeEnd,
                limit = 500,
                order = "desc",
            )
        } catch (e: Exception) {
            listError = e.message ?: "加载失败"
            items = emptyList()
        }
        if (spec.chart == ChartKind.Weight) {
            try {
                weight = vm.backend.weightChart(days)
            } catch (e: Exception) {
                chartError = e.message ?: "加载失败"
            }
        }
        if (spec.chart == ChartKind.Level) {
            try {
                level = vm.backend.levelChart()
            } catch (e: Exception) {
                chartError = e.message ?: "加载失败"
            }
        }
        if (spec.key == "food") {
            try {
                summaryJson = vm.backend.summary(if (days <= 7) "week" else "month")
            } catch (e: Exception) {
                summaryError = e.message ?: "加载失败"
            }
        }
        loading = false
    }

    Surface(modifier = Modifier.fillMaxSize(), color = GlpColors.Bg) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            // 顶栏固定：无论滚到哪里都能返回。
            //
            // 必须给不透明背景 + 底部一条分割线：否则下面的内容滚上来会**透过顶栏
            // 显示**，看起来像统计格子和标题叠在一起（截图里就是这样，很容易被
            // 当成布局写坏了）。设计系统里的 Line 只有 6% 白，在近黑底上几乎看不见，
            // 所以这里用更实的一档并补一个底色过渡。
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(GlpColors.Bg)
                    .drawBehind {
                        // 顶栏底边分割线，让"固定区"和"滚动区"在视觉上分开
                        val y = size.height - 1f
                        drawLine(
                            Color(0x1FFFFFFF),
                            Offset(0f, y),
                            Offset(size.width, y),
                            strokeWidth = 1f,
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton("‹ 返回", GlpColors.Text2) { onClose() }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${spec.label} · 详情",
                        color = GlpColors.Text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(spec.hint, color = GlpColors.Text3, fontSize = 11.sp)
                }
            }

            // 整页只有这一个竖向滚动容器
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    RANGE_DAYS.forEach { option ->
                        ChoiceChip("$option 天", days == option) {
                            if (days != option) days = option
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(rangeText, color = GlpColors.Text3, fontSize = 11.5.sp)
                if (spec.chart == ChartKind.Level) {
                    HintLine("时间范围只影响下面的剂量记录；体内水平曲线由后端按剂量推算，窗口固定。")
                }

                val rows = items.orEmpty()
                val metricKey = pickMetric(spec, rows)

                // ------------------------------------------------ 统计
                Spacer(Modifier.height(6.dp))
                GlpCard {
                    CardTitle("统计", "近 $days 天")
                    Spacer(Modifier.height(10.dp))
                    if (listError != null) {
                        ErrorState(message = "记录加载失败：$listError", onRetry = { reload += 1 })
                    } else {
                        Row(Modifier.fillMaxWidth()) {
                            DetailStatTiles(
                                spec = spec,
                                rows = rows,
                                metricKey = metricKey,
                                days = days,
                            )
                        }
                        if (loading) {
                            Spacer(Modifier.height(6.dp))
                            Text("正在加载…", color = GlpColors.Text3, fontSize = 11.sp)
                        }
                        if (spec.metrics.size > 1) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "统计指标：${metricOf(metricKey).label} · $rangeText",
                                color = GlpColors.Text3,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                // ------------------------------------------------ 趋势图 / 柱状汇总
                Spacer(Modifier.height(12.dp))
                DetailChartCard(
                    spec = spec,
                    rows = rows,
                    metricKey = metricKey,
                    days = days,
                    today = today,
                    rangeText = rangeText,
                    weight = weight,
                    level = level,
                    chartError = chartError,
                    listError = listError,
                    loading = loading,
                    waterTargetMl = waterTargetMl,
                    onRetry = { reload += 1 },
                )

                // ------------------------------------------------ 小结
                val noteRows = detailNoteRows(spec, rows, metricKey, weight)
                val foodRows = if (spec.key == "food") summaryJson?.let { foodSummaryRows(it) }.orEmpty() else emptyList()
                if (noteRows.isNotEmpty() || foodRows.isNotEmpty() || summaryError != null) {
                    Spacer(Modifier.height(12.dp))
                    GlpCard {
                        CardTitle("小结")
                        Spacer(Modifier.height(8.dp))
                        foodRows.forEach { (key, value) -> KvRow(key, value) }
                        noteRows.forEach { (key, value) -> KvRow(key, value) }
                    }
                }

                if (summaryError != null) {
                    HintLine("汇总数据加载失败：$summaryError（上面的合计来自本次记录）")
                }
                if (spec.chart == ChartKind.Level) {
                    HintLine(levelNote(level))
                }
                if (spec.key == "health") {
                    HintLine("其它健康记录（化验、血压、体征）不作严重度统计，但会出现在下面的列表里。")
                }
                if (spec.key == "sleep") {
                    HintLine("睡眠时长建议保持 7–9 小时。")
                }

                // ------------------------------------------------ 记录列表
                Spacer(Modifier.height(12.dp))
                GlpCard {
                    CardTitle(
                        "记录",
                        when {
                            listError != null -> "加载失败"
                            loading && items == null -> "加载中…"
                            rows.isEmpty() -> "暂无"
                            else -> "共 ${rows.size} 条 · 最新在前"
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    when {
                        listError != null -> Text(
                            "记录加载失败：$listError。请在上方点「重试」。",
                            color = GlpColors.Text3,
                            fontSize = 11.5.sp,
                        )
                        items == null -> Text("正在加载…", color = GlpColors.Text3, fontSize = 12.sp)
                        rows.isEmpty() -> EmptyState(spec.emptyTitle, spec.emptyHint)
                        else -> rows.forEach { record ->
                            RecordRow(record = record, onDelete = { pendingDelete = record })
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        DeleteConfirmDialog(
            record = target,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    val fresh = deleteAndAwait(vm, target.id, spec.types, rangeStart, rangeEnd)
                    if (fresh != null) items = fresh
                    reload += 1
                    onChanged()
                }
            },
        )
    }
}

/** 记录数（或最新）/ 平均 / 最高 / 最低，四格。必须是 RowScope 扩展，格子才能各自 weight。 */
@Composable
private fun RowScope.DetailStatTiles(spec: SectionSpec, rows: List<RecordView>, metricKey: String, days: Int) {
    val metric = metricOf(metricKey)
    val ofMetric = rows.filter { it.type == metricKey && it.value != null }
    val stats = valueStats(ofMetric.mapNotNull { it.value })
    var maxRow: RecordView? = null
    var minRow: RecordView? = null
    for (record in ofMetric) {
        val value = record.value ?: continue
        if (maxRow == null || value > (maxRow.value ?: 0.0)) maxRow = record
        if (minRow == null || value < (minRow.value ?: 0.0)) minRow = record
    }
    val latest = ofMetric.firstOrNull()      // 接口按 desc 返回，第一条就是最新

    StatTile(
        label = if (spec.latestFirst) "最新" else "记录数",
        value = if (spec.latestFirst) metricNumber(metricKey, latest?.value) else (stats?.count ?: 0).toString(),
        unit = if (spec.latestFirst) metric.unit else "条",
        hint = if (spec.latestFirst) {
            latest?.let { "${shortDate(it.date)} ${it.time}" } ?: "还没有记录"
        } else {
            "近 $days 天"
        },
        modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(8.dp))
    StatTile(
        label = "平均",
        value = metricNumber(metricKey, stats?.avg),
        unit = metric.unit,
        hint = if (spec.key == "water") "每次记录" else "每一条",
        modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(8.dp))
    StatTile(
        label = "最高",
        value = metricNumber(metricKey, stats?.max),
        unit = metric.unit,
        hint = maxRow?.let { "${shortDate(it.date)} ${it.time}" } ?: "",
        modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(8.dp))
    StatTile(
        label = "最低",
        value = metricNumber(metricKey, stats?.min),
        unit = metric.unit,
        hint = minRow?.let { "${shortDate(it.date)} ${it.time}" } ?: "",
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun DetailChartCard(
    spec: SectionSpec,
    rows: List<RecordView>,
    metricKey: String,
    days: Int,
    today: String,
    rangeText: String,
    weight: WeightInfo?,
    level: LevelInfo?,
    chartError: String?,
    listError: String?,
    loading: Boolean,
    waterTargetMl: Double,
    onRetry: () -> Unit,
) {
    // 图表点只在数据/指标变化时重建：每次重组都 new 一个 List 会让
    // TouchLineChart 内部的 remember(points) 重置，手指刚按下的游标会立刻消失
    val points = remember(spec.key, metricKey, rows, weight?.series, level?.series) {
        buildChartPoints(spec, rows, metricKey, weight, level)
    }

    GlpCard {
        when (spec.chart) {
            ChartKind.Weight -> {
                val title = if (metricKey == "weight") "体重趋势" else "${metricOf(metricKey).label}趋势"
                CardTitle(title, rangeText)
                Spacer(Modifier.height(8.dp))
                when {
                    chartError != null -> ErrorState(message = "趋势图加载失败：$chartError", onRetry = onRetry)
                    listError != null && metricKey != "weight" -> ChartBlocked(listError)
                    metricKey == "weight" && weight == null -> Text(
                        "正在加载…",
                        color = GlpColors.Text3,
                        fontSize = 12.sp,
                    )
                    points.isEmpty() -> EmptyState(spec.emptyTitle, spec.emptyHint)
                    else -> TouchLineChart(
                        points = points,
                        chartHeight = 200,
                        color = if (metricKey == "weight") GlpColors.Weight else metricOf(metricKey).color,
                        goalValue = if (metricKey == "weight") weight?.goal?.toFloat() else null,
                        hint = CHART_HINT,
                        xLabels = tickLabels(weight?.series?.map { it.t } ?: emptyList()),
                    )
                }
                if (metricKey == "weight" && weight?.goal != null) {
                    HintLine("虚线是目标体重 ${formatNum(weight?.goal ?: 0.0, 0)} kg。")
                }
            }

            ChartKind.Kcal -> {
                CardTitle("每日热量", rangeText)
                Spacer(Modifier.height(8.dp))
                if (listError != null) {
                    ChartBlocked(listError)
                } else if (points.isEmpty()) {
                    EmptyState(spec.emptyTitle, spec.emptyHint)
                } else {
                    TouchLineChart(
                        points = points,
                        chartHeight = 200,
                        color = GlpColors.Food,
                        goalValue = null,
                        hint = CHART_HINT,
                        xLabels = tickLabels(dayTotals(rows, metricKey).map { it.first }),
                    )
                    HintLine("只画有记录的日子，共 ${points.size} 天。")
                }
            }

            ChartKind.Sleep -> {
                CardTitle("每晚睡眠", rangeText)
                Spacer(Modifier.height(8.dp))
                if (listError != null) {
                    ChartBlocked(listError)
                } else if (points.isEmpty()) {
                    EmptyState(spec.emptyTitle, spec.emptyHint)
                } else {
                    TouchLineChart(
                        points = points,
                        chartHeight = 200,
                        color = GlpColors.Sleep,
                        goalValue = null,
                        hint = CHART_HINT,
                        xLabels = tickLabels(dayTotals(rows, metricKey).map { it.first }),
                    )
                }
            }

            ChartKind.Level -> {
                CardTitle("体内水平估算", "基于群体药代动力学")
                Spacer(Modifier.height(8.dp))
                when {
                    chartError != null -> ErrorState(message = "估算曲线加载失败：$chartError", onRetry = onRetry)
                    level == null -> Text("正在加载…", color = GlpColors.Text3, fontSize = 12.sp)
                    points.isEmpty() -> EmptyState("还没有药物水平数据", "记录一次剂量后就会显示曲线")
                    else -> {
                        TouchLineChart(
                            points = points,
                            chartHeight = 200,
                            color = GlpColors.Dose,   // 用药用琥珀色（与今天页的剂量色一致）
                            goalValue = null,
                            hint = "按住曲线拖动可读出每个时间点的估算水平",
                            xLabels = tickLabels(level?.series?.map { it.t } ?: emptyList()),
                        )
                        val first = level.series.firstOrNull()?.t
                        val last = level.series.lastOrNull()?.t
                        if (first != null && last != null) {
                            HintLine(
                                "曲线覆盖 ${shortDate(first)}–${shortDate(last)}" +
                                    if (last.take(10) > today) "（末尾含对未来的预测）" else "",
                            )
                        }
                    }
                }
            }

            ChartKind.Bars -> {
                val title = if (metricKey == "water") "每日饮水" else "每日训练"
                CardTitle(title, rangeText)
                Spacer(Modifier.height(8.dp))
                val entries = dayTotals(rows, metricKey)
                if (listError != null) {
                    ChartBlocked(listError)
                } else if (entries.isEmpty()) {
                    EmptyState(spec.emptyTitle, spec.emptyHint)
                } else {
                    val target = if (metricKey == "water") waterTargetMl else 0.0
                    val maxValue = if (target > 0) {
                        target
                    } else {
                        (entries.maxOfOrNull { it.second } ?: 1.0).coerceAtLeast(1.0)
                    }
                    entries.takeLast(MAX_BARS).forEach { (day, total) ->
                        BarRow(
                            label = dayLabel(day),
                            value = total,
                            max = maxValue,
                            color = metricOf(metricKey).color,
                            valueText = metricText(metricKey, total) +
                                if (target > 0) " / ${formatNum(target, 0)}" else "",
                        )
                    }
                    if (entries.size > MAX_BARS) {
                        HintLine("只显示最近 $MAX_BARS 天，共 ${entries.size} 天有记录。")
                    }
                    if (target > 0) {
                        HintLine("柱长按每天 ${formatNum(target, 0)} ${metricOf(metricKey).unit} 的目标缩放。")
                    }
                }
            }

            ChartKind.Severity -> {
                CardTitle("严重程度分布", rangeText)
                Spacer(Modifier.height(8.dp))
                val buckets = severityBuckets(rows)
                val total = buckets.sumOf { it.count }
                if (listError != null) {
                    ChartBlocked(listError)
                } else if (total == 0) {
                    EmptyState(
                        "还没有副作用记录",
                        "记录恶心、便秘等感受后，会按严重程度（1–10 级）分组显示",
                    )
                } else {
                    val maxCount = (buckets.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
                    buckets.forEach { bucket ->
                        BarRow(
                            label = bucket.label,
                            value = bucket.count.toDouble(),
                            max = maxCount.toDouble(),
                            color = bucket.color,
                            valueText = "${bucket.count} 次",
                        )
                    }
                    HintLine("按记录时的严重程度（1–10 级）分组，共 $total 条。")
                }
            }
        }
    }

    if (spec.chart != ChartKind.Weight && spec.chart != ChartKind.Level && loading) {
        // 加载提示只给一次，避免每张卡重复
        Spacer(Modifier.height(4.dp))
        Text("正在加载…", color = GlpColors.Text3, fontSize = 11.sp)
    }
}

/** 记录没拉到时不画图，也不假装"没有数据"——那会让人以为自己的记录丢了。 */
@Composable
private fun ChartBlocked(listError: String) {
    Text(
        "记录加载失败，暂时画不出图：$listError。请在上方点「重试」。",
        color = GlpColors.Text3,
        fontSize = 11.5.sp,
        lineHeight = 17.sp,
    )
}

@Composable
private fun BarRow(label: String, value: Double, max: Double, color: Color, valueText: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = GlpColors.Text3, fontSize = 11.sp, modifier = Modifier.width(46.dp))
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            ThinBar(value, max, color, height = 6)
        }
        Text(
            valueText,
            color = GlpColors.Text2,
            fontSize = 11.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(88.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun KvRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, color = GlpColors.Text3, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = GlpColors.Text2,
            fontSize = 12.5.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.5f),
        )
    }
}

@Composable
private fun CardTitle(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.weight(1f))
            Text(trailing, color = GlpColors.Text3, fontSize = 11.5.sp)
        }
    }
}

// ==================================================================== 版块定义

private enum class ChartKind { Weight, Kcal, Sleep, Level, Bars, Severity }

private data class SectionSpec(
    val key: String,
    val label: String,
    val hint: String,
    val types: List<String>,
    val metrics: List<String>,
    val chart: ChartKind,
    val latestFirst: Boolean,
    val emptyTitle: String,
    val emptyHint: String,
)

private data class Metric(val label: String, val unit: String, val digits: Int, val color: Color)

private data class DayInfo(
    val dots: List<String>,
    val colors: List<Color>,
    val planned: Boolean,
    val counts: Map<String, Int>,
)

private data class WeekOverview(
    val start: String,
    val end: String,
    val days: Int,
    val kcalPerDay: Double,
    val proteinPerDay: Double,
    val waterPerDayMl: Double,
    val kcalTotal: Double,
    val proteinTotal: Double,
    val targetProtein: Double?,
    val targetWaterMl: Double?,
    val targetKcal: Double?,
)

private data class ValueStats(val count: Int, val avg: Double, val max: Double, val min: Double)

private data class SeverityBucket(val label: String, val color: Color, val count: Int)

private val SECTION_SPECS = listOf(
    SectionSpec(
        key = "body",
        label = "身体",
        hint = "体重 · 体脂 · 腰围 · 肌肉",
        types = listOf("weight", "body_fat", "waist", "muscle"),
        metrics = listOf("weight", "body_fat", "waist", "muscle"),
        chart = ChartKind.Weight,
        latestFirst = true,
        emptyTitle = "还没有身体记录",
        emptyHint = "称一次体重、量一次腰围都会出现在这里",
    ),
    SectionSpec(
        key = "food",
        label = "饮食",
        hint = "餐食 · 卡路里 · 蛋白质",
        types = listOf("food"),
        metrics = listOf("food"),
        chart = ChartKind.Kcal,
        latestFirst = false,
        emptyTitle = "还没有饮食记录",
        emptyHint = "记一餐就会出现在这里，热量和蛋白质都会统计",
    ),
    SectionSpec(
        key = "training",
        label = "训练",
        hint = "训练 · 步数",
        types = listOf("training"),
        metrics = listOf("training"),
        chart = ChartKind.Bars,
        latestFirst = false,
        emptyTitle = "还没有训练记录",
        emptyHint = "走一段路、做一组训练都可以记下来",
    ),
    SectionSpec(
        key = "health",
        label = "健康",
        hint = "副作用 · 化验 · 血压",
        types = listOf("side_effect", "symptom", "vital", "measure"),
        metrics = listOf("side_effect", "symptom"),
        chart = ChartKind.Severity,
        latestFirst = false,
        emptyTitle = "还没有健康记录",
        emptyHint = "副作用、血压、化验结果都可以记下来",
    ),
    SectionSpec(
        key = "water",
        label = "饮水",
        hint = "每日饮水",
        types = listOf("water"),
        metrics = listOf("water"),
        chart = ChartKind.Bars,
        latestFirst = false,
        emptyTitle = "还没有饮水记录",
        emptyHint = "喝一杯水就记一次，按天汇总成柱状图",
    ),
    SectionSpec(
        key = "sleep",
        label = "睡眠",
        hint = "每晚睡眠时长",
        types = listOf("sleep"),
        metrics = listOf("sleep"),
        chart = ChartKind.Sleep,
        latestFirst = true,
        emptyTitle = "还没有睡眠记录",
        emptyHint = "记下昨晚睡了多久",
    ),
    SectionSpec(
        key = "dose",
        label = "用药",
        hint = "剂量记录 · 体内水平估算",
        types = listOf("dose"),
        metrics = listOf("dose"),
        chart = ChartKind.Level,
        latestFirst = true,
        emptyTitle = "还没有用药记录",
        emptyHint = "记录一次注射就会出现在这里",
    ),
)

/**
 * 数据页顶层的体内药物水平概览。
 *
 * 为什么单独做一张：原先只有点进「用药」版块才能看到曲线，顶层的统计卡片里
 * 完全没有这一项。对每天关心药效还在不在的人来说，这是最该一眼看到的数字。
 *
 * 百分比口径与今天页一致：**相对稳态峰值**（峰值=100%，谷值约 51%），
 * 不是相对谷值——否则会出现"102%"这种没法读的数字。
 */
@Composable
private fun LevelOverviewCard(
    level: LevelInfo?,
    error: String?,
    onRetry: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    GlpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("体内药物水平", color = GlpColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    "估算 · 相对稳态峰值",
                    color = GlpColors.Text3,
                    fontSize = 11.sp,
                )
            }
            PillButton("详情", GlpColors.Dose) { onOpenDetail() }
        }

        Spacer(Modifier.height(12.dp))

        when {
            error != null -> ErrorState(message = "体内水平加载失败：$error", onRetry = onRetry)

            level == null -> Text("正在加载…", color = GlpColors.Text3, fontSize = 12.sp)

            level.series.isEmpty() -> EmptyState(
                "还没有药物水平数据",
                "记录一次剂量后就会显示曲线",
            )

            else -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatNum(level.pctMax, 0),
                        color = GlpColors.Text,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("%", color = GlpColors.Text2, fontSize = 16.sp, modifier = Modifier.padding(bottom = 5.dp))
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("当前 ${formatNum(level.currentMg, 2)} mg", color = GlpColors.Text2, fontSize = 11.5.sp)
                        Text("稳态峰值 ${formatNum(level.maxMg, 2)} mg", color = GlpColors.Text3, fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(10.dp))

                val points = remember(level.series) {
                    val n = level.series.size
                    level.series.mapIndexed { i, p ->
                        ChartPoint(
                            x = if (n <= 1) 0.5f else i.toFloat() / (n - 1).toFloat(),
                            y = p.mg.toFloat(),
                            label = p.t.substringAfter('T', "").take(5),
                            value = formatNum(p.mg, 3) + " mg",
                        )
                    }
                }
                TouchLineChart(
                    points = points,
                    chartHeight = 150,
                    color = GlpColors.Dose,
                    goalValue = null,
                    hint = "按住曲线拖动可读出每个时间点的估算水平",
                    xLabels = tickLabels(level?.series?.map { it.t } ?: emptyList()),
                )

                val first = level.series.firstOrNull()?.t
                val last = level.series.lastOrNull()?.t
                if (first != null && last != null) {
                    Spacer(Modifier.height(6.dp))
                    HintLine(
                        "覆盖 ${shortDate(first)}–${shortDate(last)}" +
                            if (last.take(10) > todayIso()) "，末尾是对未来剂量的预测" else "",
                        "ℹ️",
                    )
                }
                HintLine("基于群体药代动力学的估算，并非血药浓度测量。", "⚠️")
            }
        }
    }
}

/** 今天（本地日期，YYYY-MM-DD）。 */
private fun todayIso(): String = java.time.LocalDate.now().toString()

private val METRICS: Map<String, Metric> = mapOf(
    "weight" to Metric("体重", "kg", 1, GlpColors.Weight),
    "body_fat" to Metric("体脂率", "%", 1, GlpColors.Sleep),
    "waist" to Metric("腰围", "cm", 1, GlpColors.Accent),
    "muscle" to Metric("肌肉量", "kg", 1, GlpColors.Weight),
    "food" to Metric("餐食热量", "kcal", 0, GlpColors.Food),
    "water" to Metric("饮水量", "ml", 0, GlpColors.Water),
    "training" to Metric("训练时长", "分钟", 0, GlpColors.Weight),
    "sleep" to Metric("睡眠时长", "小时", 1, GlpColors.Sleep),
    "dose" to Metric("剂量", "mg", 2, GlpColors.Dose),
    "side_effect" to Metric("副作用严重度", "级", 0, GlpColors.Food),
    "symptom" to Metric("症状严重度", "级", 0, GlpColors.Sleep),
    "vital" to Metric("体征", "", 1, GlpColors.Water),
    "measure" to Metric("测量值", "", 1, GlpColors.Accent),
    "bp" to Metric("血压", "mmHg", 0, GlpColors.Food),
    "glucose" to Metric("血糖", "mmol/L", 1, GlpColors.Water),
    "note" to Metric("备注", "", 0, GlpColors.Text2),
)

/** 图例：点色 → 中文名（与 WebView 版的 DOT_LABELS 一致）。 */
private val LEGEND = listOf(
    "dose" to "剂量",
    "weight" to "体重",
    "food" to "餐食",
    "water" to "饮水",
    "training" to "训练",
    "side_effect" to "副作用",
    "sleep" to "睡眠",
)

private val RANGE_DAYS = listOf(7, 30, 90)
private const val MAX_BARS = 30
private const val CHART_HINT = "按住图表左右拖动可读出每个点的数值"

private fun metricOf(key: String): Metric = METRICS[key] ?: Metric(key, "", 1, GlpColors.Accent)

// ==================================================================== 网络辅助

/**
 * 删除一条记录，并等到它真的从后端消失再返回最新列表。
 *
 * 为什么要等：AppViewModel.deleteRecord 把请求丢进自己的 viewModelScope 就返回了，
 * 调用方紧接着重新拉日历/列表会和 DELETE 赛跑，用户会看到"提示已删除但那一行还在"。
 * 这里让 ViewModel 走它的删除路径（Toast + bootstrap 刷新），然后用短轮询确认结果；
 * 删除失败时也会返回（此时记录还在，界面显示的就是真实状态）。
 */
private suspend fun deleteAndAwait(
    vm: AppViewModel,
    id: Int,
    types: List<String>?,
    start: String,
    end: String,
): List<RecordView>? {
    vm.deleteRecord(id)
    var fresh: List<RecordView>? = null
    for (attempt in 0 until 10) {
        if (attempt > 0) delay(150)
        fresh = try {
            vm.backend.records(types = types, start = start, end = end, limit = 500, order = "desc")
        } catch (_: Exception) {
            null
        }
        if (fresh != null && fresh.none { it.id == id }) return fresh
    }
    return fresh
}

// ==================================================================== 解析 / 计算

private fun parseWeek(o: JSONObject): WeekOverview {
    val totals = o.optJSONObject("totals") ?: JSONObject()
    val averages = o.optJSONObject("averages") ?: JSONObject()
    val targets = o.optJSONObject("targets") ?: JSONObject()
    val days = o.optInt("days", 1).coerceAtLeast(1)
    val waterMl = totals.optDouble("water_ml", 0.0)
    return WeekOverview(
        start = o.optString("start"),
        end = o.optString("end"),
        days = days,
        kcalPerDay = averages.optDouble("kcal_per_day", 0.0),
        proteinPerDay = averages.optDouble("protein_per_day", 0.0),
        // water_per_day 的单位是"升"，这里统一换算成 ml 再往上走
        waterPerDayMl = if (waterMl > 0) waterMl / days else averages.optDouble("water_per_day", 0.0) * 1000.0,
        kcalTotal = totals.optDouble("kcal", 0.0),
        proteinTotal = totals.optDouble("protein_g", 0.0),
        targetProtein = targets.optDouble("protein_g", 0.0).takeIf { it > 0 },
        targetWaterMl = targets.optDouble("water_ml", 0.0).takeIf { it > 0 },
        targetKcal = targets.optDouble("calories", 0.0).takeIf { it > 0 },
    )
}

private fun parseCalendarDays(o: JSONObject?): Map<String, DayInfo> {
    val days = o?.optJSONObject("days") ?: return emptyMap()
    val out = mutableMapOf<String, DayInfo>()
    val keys = days.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val cell = days.optJSONObject(key) ?: continue
        val dots = mutableListOf<String>()
        cell.optJSONArray("dots")?.let { arr ->
            for (i in 0 until arr.length()) dots.add(arr.optString(i))
        }
        val colors = mutableListOf<Color>()
        cell.optJSONArray("dot_colors")?.let { arr ->
            for (i in 0 until arr.length()) {
                // 保持与 dots 一一对应：解析不出来也要占位，否则索引会错位
                val fallback = fallbackDotColor(dots.getOrElse(i) { "" })
                colors.add(parseHexColor(arr.optString(i)) ?: fallback)
            }
        }
        val counts = mutableMapOf<String, Int>()
        cell.optJSONObject("types")?.let { types ->
            val typeKeys = types.keys()
            while (typeKeys.hasNext()) {
                val typeKey = typeKeys.next()
                counts[typeKey] = types.optInt(typeKey, 0)
            }
        }
        out[key] = DayInfo(
            dots = dots,
            colors = colors,
            planned = cell.optBoolean("planned", false),
            counts = counts,
        )
    }
    return out
}

/** 后端的 dot_colors 是 "#RRGGBB"；解析失败返回 null，由调用方退回本地配色。 */
private fun parseHexColor(hex: String?): Color? {
    val raw = hex?.trim()?.removePrefix("#") ?: return null
    val value = raw.toLongOrNull(16) ?: return null
    return when (raw.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> null
    }
}

private fun dotColorFor(type: String, info: DayInfo?, index: Int, dotColors: JSONObject?): Color {
    val fromCell = info?.colors?.getOrNull(index)
    if (fromCell != null) return fromCell
    return legendColor(type, dotColors)
}

private fun legendColor(type: String, dotColors: JSONObject?): Color {
    val hex = dotColors?.optString(type, "")?.takeIf { it.isNotBlank() }
    return parseHexColor(hex) ?: fallbackDotColor(type)
}

/** 与 SPA 的 DOT_COLORS 同一套映射，后端没给颜色时用它。 */
private fun fallbackDotColor(type: String): Color = when (type) {
    "dose" -> GlpColors.Dose
    "weight" -> GlpColors.Weight
    "food" -> GlpColors.Food
    "water", "lab" -> GlpColors.Water
    "training" -> GlpColors.Weight
    "side_effect", "symptom", "sleep" -> GlpColors.Sleep
    "note" -> GlpColors.Text2
    else -> GlpColors.Text3
}

private fun valueStats(values: List<Double>): ValueStats? {
    val clean = values.filter { it.isFinite() }
    if (clean.isEmpty()) return null
    return ValueStats(count = clean.size, avg = clean.average(), max = clean.max(), min = clean.min())
}

private fun pickMetric(spec: SectionSpec, rows: List<RecordView>): String {
    for (key in spec.metrics) if (hasValues(rows, key)) return key
    for (key in spec.types) if (hasValues(rows, key)) return key
    return spec.metrics.first()
}

private fun hasValues(rows: List<RecordView>, type: String): Boolean =
    rows.any { it.type == type && it.value != null && it.value.isFinite() }

/** 单条记录（升序）→ 折线点。 */
private fun localSeries(rows: List<RecordView>, type: String): List<Pair<String, Double>> =
    rows.filter { it.type == type && it.value != null && it.at.isNotBlank() }
        .sortedBy { it.at }
        .map { it.at to (it.value ?: 0.0) }

/** 同一种记录按天求和（日期升序）。 */
private fun dayTotals(rows: List<RecordView>, type: String): List<Pair<String, Double>> {
    val map = sortedMapOf<String, Double>()
    for (record in rows) {
        if (record.type != type) continue
        val value = record.value ?: continue
        val day = record.date.ifBlank { record.at.take(10) }
        if (day.isBlank()) continue
        map[day] = (map[day] ?: 0.0) + value
    }
    return map.map { it.key to it.value }
}

private fun severityBuckets(rows: List<RecordView>): List<SeverityBucket> {
    val labels = listOf("1–2 级", "3–4 级", "5–6 级", "7–8 级", "9–10 级")
    val colors = listOf(GlpColors.Weight, GlpColors.Dose, GlpColors.Food, GlpColors.Food, GlpColors.Food)
    val counts = IntArray(labels.size)
    for (record in rows) {
        if (record.type != "side_effect" && record.type != "symptom") continue
        var severity = record.value ?: continue
        // 后端允许 0–20 的刻度，>10 的按两倍折算回 1–10，别把它丢进最后一格
        if (severity > 10) severity /= 2.0
        severity = severity.coerceIn(1.0, 10.0)
        val index = ((severity - 1) / 2).toInt().coerceIn(0, labels.size - 1)
        counts[index] += 1
    }
    return labels.indices.map { SeverityBucket(labels[it], colors[it], counts[it]) }
}

private fun buildChartPoints(
    spec: SectionSpec,
    rows: List<RecordView>,
    metricKey: String,
    weight: WeightInfo?,
    level: LevelInfo?,
): List<ChartPoint> = when (spec.chart) {
    ChartKind.Weight -> if (metricKey == "weight" && weight != null && weight.series.isNotEmpty()) {
        toChartPoints(weight.series.map { it.t to it.v }, withTime = false) { metricText("weight", it) }
    } else {
        toChartPoints(localSeries(rows, metricKey), withTime = false) { metricText(metricKey, it) }
    }

    ChartKind.Kcal -> toChartPoints(dayTotals(rows, "food"), withTime = false) {
        formatNum(it, 0) + " kcal"
    }

    ChartKind.Sleep -> toChartPoints(dayTotals(rows, "sleep"), withTime = false) {
        formatNum(it, 1) + " 小时"
    }

    ChartKind.Level -> toChartPoints(level?.series?.map { it.t to it.mg } ?: emptyList(), withTime = true) {
        formatNum(it, 3) + " mg"
    }

    ChartKind.Bars, ChartKind.Severity -> emptyList()
}


/**
 * 从一串时间戳里取 3 个等距的刻度文字（首/中/末），给图表的 x 轴用。
 *
 * 只取 3 个是因为手机宽度下再多就会互相压字；要看更细的区间应该换数据
 * （范围 chips 改 days），而不是往这条轴上塞更多标签。
 */
private fun tickLabels(stamps: List<String>, withTime: Boolean = false): List<String> =
    listOfNotNull(
        stamps.firstOrNull(),
        stamps.getOrNull(stamps.size / 2),
        stamps.lastOrNull(),
    ).map { if (withTime) shortDate(it) + " " + it.substringAfter('T', "").take(5) else shortDate(it) }
        .distinct()

private fun toChartPoints(
    pairs: List<Pair<String, Double>>,
    withTime: Boolean,
    valueText: (Double) -> String,
): List<ChartPoint> {
    if (pairs.isEmpty()) return emptyList()
    val n = pairs.size
    return pairs.mapIndexed { index, (t, v) ->
        ChartPoint(
            x = if (n <= 1) 0.5f else index.toFloat() / (n - 1).toFloat(),
            y = v.toFloat(),
            label = if (withTime) shortDate(t) + " " + t.substringAfter('T', "").take(5) else shortDate(t),
            value = valueText(v),
        )
    }
}

private fun detailNoteRows(
    spec: SectionSpec,
    rows: List<RecordView>,
    metricKey: String,
    weight: WeightInfo?,
): List<Pair<String, String>> {
    val metric = metricOf(metricKey)
    if (spec.key == "body") {
        val points = localSeries(rows, metricKey)
        if (points.isEmpty()) return emptyList()
        val first = points.first()
        val last = points.last()
        val out = mutableListOf(
            "起始" to metricText(metricKey, first.second) + " · " + shortDate(first.first),
            "现在" to metricText(metricKey, last.second) + " · " + shortDate(last.first),
        )
        out += if (points.size > 1) {
            val delta = last.second - first.second
            "变化" to (if (delta > 0) "+" else "") + formatNum(delta, 1) +
                (if (metric.unit.isNotBlank()) " ${metric.unit}" else "") + "（共 ${points.size} 次）"
        } else {
            "变化" to "只有一次记录"
        }
        if (metricKey == "weight") {
            val goal = weight?.goal
            out += "目标" to if (goal != null && goal > 0) {
                formatNum(goal, 0) + " kg"
            } else {
                "还没有设置目标"
            }
        }
        return out
    }

    val entries = dayTotals(rows, metricKey)
    val out = mutableListOf<Pair<String, String>>()
    if (entries.isNotEmpty()) {
        val total = entries.sumOf { it.second }
        out += "合计" to metricText(metricKey, total)
        out += "日均" to metricText(metricKey, total / entries.size) + " / 天"
        out += "有记录" to "${entries.size} 天"
    }
    val stats = valueStats(rows.filter { it.type == metricKey }.mapNotNull { it.value })
    if (stats != null) out += "记录数" to "${stats.count} 条"
    if (spec.key == "sleep") {
        val points = localSeries(rows, metricKey)
        if (points.isNotEmpty()) {
            val last = points.last()
            out += "最近一次" to metricText(metricKey, last.second) + " · " + shortDate(last.first)
        }
    }
    return out
}

private fun foodSummaryRows(o: JSONObject): List<Pair<String, String>> {
    val totals = o.optJSONObject("totals") ?: JSONObject()
    val averages = o.optJSONObject("averages") ?: JSONObject()
    val targets = o.optJSONObject("targets") ?: JSONObject()
    val label = if (o.optString("period") == "week") "本周" else "本月"
    val kcalTarget = targets.optDouble("calories", 0.0)
    val proteinTarget = targets.optDouble("protein_g", 0.0)
    return listOf(
        "${label}合计" to formatNum(totals.optDouble("kcal", 0.0), 0) + " kcal",
        "${label}蛋白质" to formatNum(totals.optDouble("protein_g", 0.0), 0) + " g",
        "日均热量" to formatNum(averages.optDouble("kcal_per_day", 0.0), 0) + " kcal" +
            if (kcalTarget > 0) " / 目标 ${formatNum(kcalTarget, 0)}" else "",
        "日均蛋白" to formatNum(averages.optDouble("protein_per_day", 0.0), 0) + " g" +
            if (proteinTarget > 0) " / 目标 ${formatNum(proteinTarget, 0)}" else "",
        "碳水 / 脂肪 / 纤维" to listOf("carb_g", "fat_g", "fiber_g")
            .joinToString(" / ") { formatNum(totals.optDouble(it, 0.0), 0) } + " g",
    )
}

/** 体内水平必须写成"估算"：这是全应用最容易误导人的地方（与今天页口径一致）。 */
private fun levelNote(level: LevelInfo?): String {
    val disclaimer = level?.disclaimer?.takeIf { it.isNotBlank() }
        ?: "基于群体药代动力学的推算，并非医疗测量。"
    return "估算（非血药浓度）：$disclaimer"
}

private fun metricNumber(key: String, value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    val metric = metricOf(key)
    return if (metric.digits > 0) {
        formatNum(value, metric.digits)
    } else {
        formatNum(value.roundToInt().toDouble(), 0)
    }
}

private fun metricText(key: String, value: Double?): String {
    val number = metricNumber(key, value)
    if (number == "—") return number
    val unit = metricOf(key).unit
    return if (unit.isNotBlank()) "$number $unit" else number
}

/** 记录行右侧的数值文案（优先用记录自己的单位）。 */
private fun recordValueText(record: RecordView): String {
    val value = record.value
    if (value == null || !value.isFinite()) return record.subtitle.ifBlank { "—" }
    return when (record.type) {
        "side_effect", "symptom" -> formatNum(value, 0) + "/10"
        "training" -> formatNum(value, 0) + " 分钟"
        else -> formatNum(value, 1) + if (record.unit.isNotBlank()) " ${record.unit}" else ""
    }
}

private fun weekRangeText(week: WeekOverview?): String {
    if (week == null || week.start.length < 10) return "本周"
    return "${shortDate(week.start)} – ${shortDate(addDays(week.start, 6))}"
}

private fun dayLabel(day: String): String {
    val parts = day.split("-")
    if (parts.size < 3) return day
    return "${parts[1].toIntOrNull() ?: 0}/${parts[2].toIntOrNull() ?: 0}"
}

// ==================================================================== 日期辅助

private fun localToday(): String = LocalDate.now().toString()

private fun addDays(day: String, amount: Int): String {
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    return date.plusDays(amount.toLong()).toString()
}

private fun mondayOf(day: String): String {
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    return date.minusDays((date.dayOfWeek.value - 1).toLong()).toString()
}

private fun weekdayIndex(day: String): Int {
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return 0
    return date.dayOfWeek.value - 1
}

/** '2026-10' + (-1) → '2026-09'（纯月份运算，不经过 Date）。 */
private fun shiftMonth(month: String, delta: Int): String {
    val parts = month.split("-")
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return month
    val monthNum = parts.getOrNull(1)?.toIntOrNull() ?: return month
    val total = year * 12 + (monthNum - 1) + delta
    val newYear = Math.floorDiv(total, 12)
    val newMonth = total - newYear * 12 + 1
    return String.format(Locale.US, "%04d-%02d", newYear, newMonth)
}

private val MONTH_CN = listOf(
    "一月", "二月", "三月", "四月", "五月", "六月",
    "七月", "八月", "九月", "十月", "十一月", "十二月",
)

/** 后端还没返回 month_cn 时的兜底标题（与后端格式一致：'十月 2026'）。 */
private fun monthTitleFallback(month: String): String {
    val parts = month.split("-")
    val year = parts.getOrNull(0) ?: return month
    val monthNum = parts.getOrNull(1)?.toIntOrNull() ?: return month
    val name = MONTH_CN.getOrElse(monthNum - 1) { month }
    return "$name $year"
}
