package com.glpmate.native_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glpmate.native_app.data.AppViewModel
import com.glpmate.native_app.data.PythonBackend
import com.glpmate.native_app.notify.ReminderScheduler
import com.glpmate.native_app.ui.ErrorState
import com.glpmate.native_app.ui.GlpColors
import com.glpmate.native_app.ui.GlpMateTheme
import com.glpmate.native_app.ui.CoachScreen
import com.glpmate.native_app.ui.DataScreen
import com.glpmate.native_app.ui.FoodEntryOverlay
import com.glpmate.native_app.ui.RecordEntryOverlay
import com.glpmate.native_app.ui.SettingsScreen
import com.glpmate.native_app.ui.TodayScreen

/**
 * 原生版入口。
 *
 * 导航用最朴素的"状态 + when"而不是 Navigation Compose：
 * 只有 4 个平级标签页、没有深层路由，引一个导航库只会增加依赖和心智负担。
 * 真需要深层链接或返回栈时再换不迟。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            android.util.Log.i(
                "GlpReminder",
                if (granted) "通知权限已授予，提醒可以显示"
                else "通知权限被拒绝：提醒时间仍会到点，但看不到通知",
            )
        }

    /**
     * Android 13+ 发通知要运行时授权。
     * 不申请的话提醒会被系统静默丢掉——用户会以为"提醒功能坏了"，
     * 所以启动时就问，并把结果写进日志方便排查。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // 调试开关：带 -PnoNotifPrompt 构建时不弹权限框，直接进界面。
        // 用途：手机上屏幕锁着的时候，权限弹窗会挡住整个应用，连界面都看不到。
        // 正式使用请不带这个开关构建——没有通知权限时提醒会被系统静默丢弃。
        val skipPrompt = try {
            val flags = packageManager.getApplicationInfo(packageName, 0).metaData
            flags?.getBoolean("glpmate.skipNotificationPrompt", false) ?: false
        } catch (e: Exception) {
            false
        }
        if (skipPrompt) {
            android.util.Log.i("GlpReminder", "已跳过通知权限请求（调试构建）")
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        // 启动顺序很重要：内嵌 CPython 后端必须先起来，前端才连得上。
        // 放在后台线程做（解压 + 起解释器要几百毫秒到几秒），界面先显示"正在连接"。
        //
        // ===== 这里踩过一个很难发现的坑，别改回 vmHolder[0]?.connect() =====
        // AppRoot 里的 onViewModelReady 是在 LaunchedEffect 里回调的，也就是**首帧组合
        // 之后**才执行；而下面那个 Thread 是 setContent 之后立刻启动的。两者谁先到是
        // 不确定的——模拟器上 CPython 起得比首帧快，于是回调发生前 vmHolder[0] 还是 null，
        // `?.` 静默吞掉，connect() 永远不执行，界面**永远停在"正在连接后端…"且没有任何
        // 报错**（这个 bug 在真机上恰好没触发，因为真机 Python 启动更慢）。
        //
        // 正确做法：两边都只负责"记录自己好了"，谁后到谁触发连接——这样后端先好
        // 还是界面先好都不会漏。用 AtomicReference 而不是局部 @Volatile 变量
        // （注解不能用在局部变量上）。
        val vmRef = java.util.concurrent.atomic.AtomicReference<AppViewModel?>(null)
        val backendRef = java.util.concurrent.atomic.AtomicReference<Result<String>?>(null)
        val connectOnce = java.util.concurrent.atomic.AtomicBoolean(false)

        fun tryConnect() {
            val vm = vmRef.get() ?: return
            val result = backendRef.get() ?: return
            if (!connectOnce.compareAndSet(false, true)) return
            result.fold(
                onSuccess = { vm.connect() },
                onFailure = { error ->
                    vm.reportBackendFailure(error.message ?: error.javaClass.simpleName)
                },
            )
        }

        setContent {
            GlpMateTheme {
                AppRoot(onViewModelReady = { vm ->
                    vmRef.set(vm)
                    tryConnect()
                })
            }
        }
        Thread {
            backendRef.set(PythonBackend.ensureStarted(applicationContext))
            tryConnect()
        }.start()
    }
}

private enum class Tab(val label: String, val icon: String) {
    Today("今天", "🏠"),
    Data("数据", "📊"),
    Coach("助手", "💬"),
    Settings("设置", "⚙"),
}

@Composable
private fun AppRoot(
    onViewModelReady: (AppViewModel) -> Unit = {},
    vm: AppViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { onViewModelReady(vm) }

    // 后端每次下发新的时间表就重排本地闹钟。闹钟只读缓存，不依赖后端在跑，
    // 所以进程被杀之后提醒仍然准点。
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheduleJson = state.bootstrap?.scheduleJson
    LaunchedEffect(scheduleJson) {
        if (scheduleJson != null) {
            ReminderScheduler.syncFromSchedule(
                context.applicationContext,
                org.json.JSONArray(scheduleJson),
            )
        }
    }
    var tab by remember { mutableStateOf(Tab.Today) }
    // 子界面打开全屏浮层时把底部标签栏藏起来（TabBar 在这一层，子界面盖不住它）
    var overlayOpen by remember { mutableStateOf(false) }
    // 饮食录入浮层：null = 关闭；"manual"/"photo"/"text" = 打开并指定初始方式
    var foodMode by remember { mutableStateOf<String?>(null) }
    // 记录录入浮层：null = 关闭；否则是记录类型（weight/water/dose/side_effect/…）
    var entryType by remember { mutableStateOf<String?>(null) }

    // 一次性提示走 Snackbar，显示后立刻清掉，避免重组时重复弹
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.toast) {
        val message = state.toast
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            vm.clearToast()
        }
    }

    Box(Modifier.fillMaxSize().background(GlpColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when {
                    state.connecting -> CenterMessage("正在连接后端…", "首次启动需要几秒钟")

                    !state.connected -> ErrorState(
                        message = state.error ?: "连不上后端",
                        onRetry = { vm.connect() },
                    )

                    else -> when (tab) {
                        Tab.Today -> TodayScreen(
                            data = state.bootstrap,
                            busy = state.busy,
                            onAddWater = { ml ->
                                vm.addRecord(
                                    type = "water",
                                    at = vm.nowIso(),
                                    value = ml.toDouble(),
                                    unit = "ml",
                                    meta = mapOf("amount_ml" to ml),
                                    successMessage = "已记录饮水 $ml ml",
                                )
                            },
                            onOpenFood = { mode ->
                                foodMode = mode
                                overlayOpen = true
                            },
                            onQuickRecord = { type ->
                                entryType = type
                                overlayOpen = true

                                // 剂量表单要按说明书核准的阶梯给选项，那些阶梯来自
                                // /api/drugs，只有设置页会去拉。所以打开剂量表单前先补拉一次，
                                // 否则用户只能看到当前这一个剂量档位。
                                if (type == "dose") vm.loadSettingsData()
                            },
                        )

                        Tab.Data -> DataScreen(
                            state = state,
                            vm = vm,
                            onOverlayChange = { overlayOpen = it },
                        )
                        Tab.Coach -> CoachScreen(state = state, vm = vm)
                        Tab.Settings -> SettingsScreen(state = state, vm = vm)
                    }
                }
            }

            if (!overlayOpen) {
                TabBar(current = tab, onSelect = { tab = it })
            }
        }

        // 必须放在根 Box 的最后一个子节点：否则底部标签栏会盖在浮层上面
        entryType?.let { type ->
            RecordEntryOverlay(
                type = type,
                state = state,
                vm = vm,
                onDismiss = {
                    entryType = null
                    overlayOpen = false
                },
            )
        }

        foodMode?.let { mode ->
            FoodEntryOverlay(
                state = state,
                vm = vm,
                initialMode = mode,
                onDismiss = {
                    foodMode = null
                    overlayOpen = false
                },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
        ) { data -> Snackbar(snackbarData = data) }
    }
}

@Composable
private fun CenterMessage(title: String, subtitle: String? = null) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = GlpColors.Text2, fontSize = 15.sp)
        if (subtitle != null) {
            Text(subtitle, color = GlpColors.Text3, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = GlpColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = GlpColors.Text3, fontSize = 12.5.sp)
        Text(
            "这个界面在原生版里还没实现（WebView 版仍然可用）",
            color = GlpColors.Text3,
            fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(GlpColors.Surface)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { entry ->
            val active = entry == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 14.dp, vertical = 2.dp),
            ) {
                Text(entry.icon, fontSize = 19.sp)
                Text(
                    entry.label,
                    color = if (active) GlpColors.Accent else GlpColors.Text3,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
