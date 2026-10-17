package com.liteagent.app.capability

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.StatFs
import com.liteagent.shared.DeviceProfile
import java.io.File

/**
 * 设备能力探测。
 *
 * 三条硬规则（方案 v2 约束 9 的代码兑现）：
 * 1. 每个字段独立兜底，任何一个探测抛异常都不能让整个 collect() 失败；
 * 2. 失败不是静默吞掉，记进 [DeviceProfile.failures]，否则某机型拿不到 SoC 时无从排查；
 * 3. 不申请任何权限，全部走公开 API 与 /proc、/sys 只读文件。
 */
class DeviceProfiler(private val context: Context) {

    fun collect(): DeviceProfile {
        val failures = mutableListOf<String>()

        fun <T> safe(name: String, fallback: T, block: () -> T): T = try {
            block()
        } catch (t: Throwable) {
            failures += "$name:${t.javaClass.simpleName}"
            fallback
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        safe("memoryInfo", Unit) {
            am?.getMemoryInfo(memInfo)
            Unit
        }

        val cpuCores = safe("cpuCores", 1) { Runtime.getRuntime().availableProcessors() }
        val maxFreqMhz = safe<Int?>("cpuMaxFreq", null) { readCpuMaxFreqMhz() }

        val freeStorageMb = safe("freeStorage", 0L) {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong / MB
        }

        val glesVersion = safe("glesVersion", "") {
            val info = am?.deviceConfigurationInfo
            if (info == null) "" else "${info.reqGlEsVersion shr 16}.${info.reqGlEsVersion and 0xFFFF}"
        }

        val gpuRenderer = safe<String?>("glRenderer", null) { queryGlRenderer() }

        val socModel = safe<String?>("socModel", null) { readSocModel() }

        val abis = safe("abis", emptyList<String>()) { Build.SUPPORTED_ABIS.toList() }

        return DeviceProfile(
            socModel = socModel ?: Build.HARDWARE,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            board = Build.BOARD.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            cpuCores = cpuCores,
            cpuMaxFreqMhz = maxFreqMhz,
            // 必须用 totalMem：memoryClass 只是 Java 堆上限，与 native 分配无关，
            // 而端侧 LLM 的权重几乎全在 native 侧
            totalMemMb = memInfo.totalMem / MB,
            availMemMb = memInfo.availMem / MB,
            memoryClassMb = safe("memoryClass", 0) { am?.memoryClass ?: 0 },
            maxHeapMb = safe("maxHeap", 0L) { Runtime.getRuntime().maxMemory() / MB },
            freeStorageMb = freeStorageMb,
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = abis,
            glesVersion = glesVersion,
            gpuRenderer = gpuRenderer,
            isArm64 = abis.contains("arm64-v8a"),
            collectedAt = System.currentTimeMillis(),
            failures = failures
        )
    }

    /** API 31+ 才有 SOC_MODEL；更老的机型回退 Build.HARDWARE（值是平台代号，如 "qcom"）。 */
    private fun readSocModel(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val model = Build.SOC_MODEL
        return model.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
    }

    /**
     * 读每个 CPU 核的 cpufreq 上限，取最大值。
     * 部分机型该路径不可读（SELinux），读不到返回 null，不抛异常。
     */
    private fun readCpuMaxFreqMhz(): Int? {
        val cpuRoot = File("/sys/devices/system/cpu")
        val cpuDirs = cpuRoot.listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu\\d+")) }
            ?: return null
        var maxKhz = 0
        for (dir in cpuDirs) {
            val freqFile = File(dir, "cpufreq/cpuinfo_max_freq")
            if (!freqFile.canRead()) continue
            val khz = freqFile.readTextOrNull()?.toIntOrNull() ?: continue
            if (khz > maxKhz) maxKhz = khz
        }
        return if (maxKhz > 0) maxKhz / 1000 else null
    }

    private fun File.readTextOrNull(): String? = try {
        readText().trim()
    } catch (t: Throwable) {
        null
    }

    /**
     * 用离屏 EGL 取 GL_RENDERER。
     *
     * 为什么绕这一圈：Android 没有公开 API 能给到 GPU 型号字符串，
     * 而 MNN 的 GPU backend 是 OpenCL —— 判断某机型能否走 GPU，
     * 只能靠 GPU 型号（Adreno / Mali / Immortalis）+ GLES 版本推断。
     * 查 Vulkan feature flag 在这里是无效的：MNN 不用 Vulkan。
     */
    private fun queryGlRenderer(): String? {
        var display: EGLDisplay? = null
        var surface: EGLSurface? = null
        var eglContext: EGLContext? = null
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)) return null
            if (numConfigs[0] == 0) return null

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return null

            val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) return null

            GLES20.glGetString(GLES20.GL_RENDERER)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching {
                val d = display
                if (d != null && d != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    val s = surface
                    if (s != null && s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d, s)
                    val c = eglContext
                    if (c != null && c != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(d, c)
                    EGL14.eglTerminate(d)
                }
            }
        }
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
