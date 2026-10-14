package com.glpmate.native_app.data

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 内嵌 Python 后端的启动器。
 *
 * 为什么要内嵌而不是"让用户另外跑一个服务"：
 * 之前那种做法（后端在另一个 App / Termux 里）有个致命问题——Android 会冻结
 * 后台进程，前端一启动就连不上。把后端放进同一个 APK、同一个进程，就没有这个
 * 依赖关系，也不需要用户做任何额外操作。
 *
 * 流程：
 *   1. 首次运行把 assets/pyapp 解压到 filesDir（带版本戳，升版本才重解）
 *   2. 起 CPython（Chaquopy）
 *   3. 调用 glpmate_android.start_server(...)，绑定 127.0.0.1
 *
 * 数据目录刻意放在解压目录**之外**（filesDir/data）：重新解压不会冲掉用户的记录。
 */
object PythonBackend {

    private const val TAG = "GlpBackend"
    private const val ASSET_DIR = "pyapp"
    /**
     * 解压戳记。**只认版本号字符串是个坑**：改了 assets 里的 Python 代码但忘了
     * 手动 +1，设备上就永远跑旧代码（本机踩过：后端窗口逻辑改了、APK 也重装了，
     * 但解压目录里的 store.py 一直没更新，界面表现和改动前完全一样，极难发现）。
     *
     * 所以这里用**源码内容的哈希**：任何 .py 改动都会自动触发重新解压。
     * 当前哈希：f81be186134f
     */
    private const val STAMP_VERSION = "code-eae94acf6327"
    private const val PORT = 8848

    @Volatile
    private var started = false

    @Volatile
    var lastMessage: String = ""
        private set

    /** 启动后端；返回给用户看的中文结果说明。失败不抛异常，把原因写进 lastMessage。 */
    @Synchronized
    fun ensureStarted(context: Context): Result<String> {
        if (started) return Result.success(lastMessage)

        return try {
            val workspace = context.filesDir
            val pyRoot = File(workspace, "pyapp")
            val codeDir = File(pyRoot, "code")
            val staticDir = File(pyRoot, "static")
            val dataDir = File(workspace, "data")
            if (!dataDir.isDirectory && !dataDir.mkdirs()) {
                return Result.failure(IOException("无法创建数据目录 $dataDir"))
            }

            if (needsExtract(workspace)) {
                extractAssets(context, ASSET_DIR, pyRoot)
                writeStamp(workspace)
                log("解压完成 → $pyRoot")
            }

            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
                log("CPython 已启动：${Python.getInstance().getModule("sys").get("version")}")
            }

            val module: PyObject = Python.getInstance().getModule("glpmate_android")
            val result: PyObject = module.callAttr(
                "start_server",
                staticDir.absolutePath,
                codeDir.absolutePath,
                dataDir.absolutePath,
                PORT,
                true,
            )
            lastMessage = result.toString()
            log("start_server → $lastMessage")
            started = true
            Result.success(lastMessage)
        } catch (t: Throwable) {
            val reason = "${t.javaClass.simpleName}: ${t.message}"
            lastMessage = reason
            log("启动失败：$reason")
            Result.failure(t)
        }
    }

    /** 后端是否在跑（供界面显示状态）。 */
    fun isRunning(): Boolean = started

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    // ------------------------------------------------------------ 解压
    private fun needsExtract(workspace: File): Boolean {
        val stamp = File(workspace, ".pyapp-stamp")
        if (!stamp.isFile) return true
        return try {
            stamp.readText().trim() != STAMP_VERSION
        } catch (e: IOException) {
            true
        }
    }

    private fun writeStamp(workspace: File) {
        try {
            File(workspace, ".pyapp-stamp").writeText(STAMP_VERSION)
        } catch (e: IOException) {
            log("写版本戳失败：${e.message}")
        }
    }

    private fun extractAssets(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            copyFile(context, assetPath, target)
            return
        }
        if (!target.isDirectory && !target.mkdirs()) {
            throw IOException("无法创建目录 $target")
        }
        for (child in children) {
            extractAssets(context, "$assetPath/$child", File(target, child))
        }
    }

    private fun copyFile(context: Context, assetPath: String, target: File) {
        target.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("无法创建目录 $parent")
            }
        }
        context.assets.open(assetPath).use { input: InputStream ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }
}
