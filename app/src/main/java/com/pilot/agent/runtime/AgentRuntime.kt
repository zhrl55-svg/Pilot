package com.pilot.agent.runtime

import com.pilot.agent.PilotApp
import com.pilot.agent.action.AppLaunchIndex
import com.pilot.agent.action.DeviceActions
import com.pilot.agent.agent.AgentEvent
import com.pilot.agent.agent.AgentLoop
import com.pilot.agent.agent.AgentSettings
import com.pilot.agent.agent.CloudPlanner
import com.pilot.agent.agent.Guard
import com.pilot.agent.agent.HeuristicPlanner
import com.pilot.agent.agent.LocalPlanner
import com.pilot.agent.agent.PlannerRouter
import com.pilot.agent.agent.SkillStore
import com.pilot.agent.perception.ScreenReader
import com.pilot.agent.perception.ScreenSnapshot
import com.pilot.agent.service.AgentAccessibilityService
import com.pilot.agent.voice.LocalAsr
import com.pilot.agent.voice.LocalLlm
import com.pilot.agent.voice.Speaker
import com.pilot.agent.voice.VoiceprintGate
import com.pilot.agent.voice.VoiceInput
import com.pilot.agent.voice.WakeWordDetector
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 轻量依赖容器。无障碍服务连上/断开时整体重建，
 * 避免把 AccessibilityService 引用泄漏到长生命周期对象里。
 */
object AgentRuntime {

    @Volatile
    private var serviceRef: AgentAccessibilityService? = null

    @Volatile
    var reader: ScreenReader? = null
        private set

    @Volatile
    var actions: DeviceActions? = null
        private set

    @Volatile
    var loop: AgentLoop? = null
        private set

    val skills: SkillStore by lazy { SkillStore(File(PilotApp.instance.filesDir, "skills")) }
    val guard: Guard by lazy { Guard(PilotApp.instance) }

    /** 端侧语音识别（sherpa-onnx + SenseVoice）。常驻单例，模型加载一次。 */
    val asr: LocalAsr by lazy { LocalAsr(PilotApp.instance) }

    /** 端侧对话模型（MNN-LLM + Qwen3-0.6B）。单例，加载后常驻约 300MB。 */
    val localLlm: LocalLlm by lazy { LocalLlm.get(PilotApp.instance) }

    /** 播报：系统 TTS / MiniMax，按设置路由 */
    val speaker: Speaker by lazy { Speaker(PilotApp.instance) }

    /** 唤醒词检测（sherpa KWS，模型在 assets） */
    val wakeDetector: WakeWordDetector by lazy { WakeWordDetector(PilotApp.instance) }

    /** 声纹门禁（WeSpeaker，模型在 filesDir） */
    val voiceprint: VoiceprintGate by lazy { VoiceprintGate(PilotApp.instance) }

    /**
     * 语音输入（**进程级单例**）。
     *
     * 为什么提到 Runtime：导航界面要能直接触发识别
     * （`TriggerActivity` 收 `start_voice` intent），而它拿不到 ViewModel。
     * 回调是可变的（见 VoiceInput），ViewModel 每次 attach 时替换即可。
     */
    @Volatile
    var voiceInput: VoiceInput? = null

    /** 拿（或创建）语音输入单例。 */
    fun ensureVoiceInput(): VoiceInput {
        voiceInput?.let { return it }
        return VoiceInput(
            context = PilotApp.instance,
            asr = asr,
            wake = wakeDetector,
            gate = voiceprint,
        ).also { voiceInput = it }
    }

    /**
     * App 内入口索引。建一次要 1~3 秒（100+ App × 平均 10 个 Activity），
     * 放后台建，建好之前 [HeuristicPlanner] 会自动跳过这一层。
     */
    val appIndex by lazy { AppLaunchIndex(PilotApp.instance.packageManager) }

    /**
     * 常驻作用域。Agent 要能在没有界面的时候跑（语音唤起、系统键、外部广播），
     * 所以不能挂在 Activity 的 lifecycleScope 上。
     *
     * 用 Default 而不是 Main：agent 循环里全是 Binder 跨进程调用
     * （rootInActiveWindow、dispatchGesture、getInstalledApplications），
     * 放主线程会被 Compose 重组和动画拖死——实测表现为 run 刚启动就卡住。
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 事件总线放在 runtime 而不是 AgentLoop 里：服务重连会重建 AgentLoop，
     * 但 UI 的订阅不应该跟着断。
     */
    val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)

    private val _foreground = MutableStateFlow("")
    val foreground: StateFlow<String> = _foreground.asStateFlow()

    val connected: Boolean get() = serviceRef != null

    fun attach(service: AgentAccessibilityService) {
        serviceRef = service
        val r = ScreenReader(service)
        val a = DeviceActions(service, r)
        val cloud = CloudPlanner { AgentSettings.load() }
        val localPlanner = LocalPlanner { localLlm }
        val router = PlannerRouter(
            { AgentSettings.load() },
            cloud,
            HeuristicPlanner { appIndex.takeIf { it.built } },
            localPlanner,
        )
        reader = r
        actions = a
        loop = AgentLoop(r, a, router, skills, guard, { AgentSettings.load() }, events)
    }

    fun detach() {
        loop?.cancel()
        serviceRef = null
        reader = null
        actions = null
        loop = null
    }

    fun onForegroundChanged(pkg: String) {
        if (pkg.isNotEmpty()) _foreground.value = pkg
    }

    fun snapshot(): ScreenSnapshot? = reader?.last()

    fun captureNow(): ScreenSnapshot? = reader?.capture()
}
