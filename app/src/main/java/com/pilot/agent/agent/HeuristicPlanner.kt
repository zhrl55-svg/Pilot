package com.pilot.agent.agent

import com.pilot.agent.perception.UiNode

/**
 * 端侧快思考：纯规则，零延迟、零成本、离线可用。
 *
 * 它不做复杂推理，只解决两类事：
 * 1. 单动词指令（打开X / 返回 / 回桌面 / 点击X / 输入X）——占用户请求的大头；
 * 2. 云端不可用时的降级兜底，保证 agent 不是个哑巴。
 *
 * 真正需要多步推理的请求由 [CloudPlanner] 接管，路由逻辑在 [PlannerRouter]。
 */
class HeuristicPlanner(
    /** App 内入口索引，建好之前返回 null（索引要 1~3 秒） */
    private val appIndexProvider: () -> com.pilot.agent.action.AppLaunchIndex? = { null },
) : Planner {

    override val id: String = "heuristic"

    override suspend fun decide(req: AgentRequest): AgentDecision {
        val goal = req.goal.trim()
        val snap = req.snapshot

        // ★ 多步任务直接让位给下游（端侧小模型 / 云端）。
        //   规则的能力边界是「一句话一个动作」，从长句里抠动词必然出错：
        //   实测「打开设置然后点电池」被 ruleOpen 整句当成应用名，连开 4 步的错 App。
        if (isMultiStep(goal)) {
            return AgentDecision(
                thought = "多步任务，规则不参与",
                action = AgentAction(action = "unable", summary = "交给下游规划器"),
            )
        }

        // ★ 系统能力直调：音量、亮度、打电话——不发 Intent、不开界面，直接改状态
        ruleSystemOp(goal)?.let { return AgentDecision("系统能力直调", it) }

        // ★ 能用一个 Intent 启动的，绝不去点屏幕。
        //   顺序：系统动作/设置页（静态表） → App 内入口（运行时索引） → 应用启动 → 点击兜底。
        // 直达每个任务只试一次：成败都不再试。
        // 失败还重试的话会反复发同一个 Intent（实测「打开电池」连发 3 次直到死循环检测中止），
        // 而落点被厂商劫持这种情况，重试一万次结果也一样。
        val shortcutTried = req.history.any { it.startsWith("直达") }
        if (!shortcutTried) com.pilot.agent.action.ActionCatalog.resolve(goal)?.let { e ->
            return AgentDecision(
                thought = "直达：${e.label}",
                action = AgentAction(
                    action = "shortcut",
                    target = e.uri ?: e.action,
                    summary = e.label,
                ),
            )
        }

        // App 内功能（扫一扫、收款码这类 exported 入口）
        appIndexProvider()?.resolve(goal)?.let { e ->
            val label = "${e.appLabel} ${e.label}"
            val failedBefore = req.history.any { it.startsWith("直达 $label") && it.contains("fail") }
            if (!failedBefore) {
                return AgentDecision(
                    thought = "App 内入口：$label",
                    action = AgentAction(
                        action = "component",
                        target = "${e.component.packageName}/${e.component.className}",
                        summary = label,
                    ),
                )
            }
        }

        // 已经开过 App 还没找到目标，先别乱点
        ruleOpen(goal, req.history)?.let { return AgentDecision("识别为打开应用指令", it) }
        ruleKey(goal, req.history)?.let { return AgentDecision("识别为系统按键指令", it) }
        ruleInput(goal, snap, req.history)?.let { return AgentDecision("找到输入框，填入内容", it) }
        ruleTap(goal, snap, req.history)?.let { return AgentDecision("按文本匹配到目标节点", it) }
        ruleScroll(goal, snap)?.let { return AgentDecision("需要滚动查看更多内容", it) }

        // 规则命中不了：交给上层升级到云端规划器，而不是硬猜
        return AgentDecision(
            thought = "本地规则无法解析这个请求",
            action = AgentAction(action = "unable", summary = "端侧规则未覆盖：$goal"),
        )
    }

    /**
     * 是不是多步任务。
     *
     * 判据刻意简单粗暴——规则层只要有一点犹豫就不该插手：
     * 有连接词，或者出现两个以上不同动词，就算多步。
     */
    private fun isMultiStep(goal: String): Boolean {
        val connectors = listOf("然后", "接着", "并且", "之后", "顺便", "再帮", "再点", "依次", "最后")
        if (connectors.any { goal.contains(it) }) return true
        val verbs = listOf("打开", "启动", "点击", "点开", "输入", "搜索", "返回", "发送", "滑动", "滚动")
        return verbs.count { goal.contains(it) } >= 2
    }

    /**
     * 系统能力直调。
     *
     * 「声音大一点」「亮度小一点」这类请求，用系统 API 一次调用就完成，
     * 不需要打开设置页、不需要点滑块。这是「执行力」和「导航」的分界线。
     */
    private fun ruleSystemOp(goal: String): AgentAction? {
        // 音量
        if (goal.contains("音量") || goal.contains("声音")) {
            com.pilot.agent.action.SystemOps.parseDelta(goal)?.let { d ->
                return AgentAction(
                    action = "systemop", target = "volume",
                    text = d.toString(), summary = "音量",
                )
            }
        }
        // 亮度
        if (goal.contains("亮度") || goal.contains("屏幕亮")) {
            com.pilot.agent.action.SystemOps.parseDelta(goal)?.let { d ->
                return AgentAction(
                    action = "systemop", target = "brightness",
                    text = d.toString(), summary = "亮度",
                )
            }
        }
        // 打电话：给X打电话 / 拨打X
        if (goal.contains("打电话") || goal.contains("拨打") || goal.contains("呼叫")) {
            val m = Regex("(?:给|打给|拨打|呼叫|打)\\s*([\\u4e00-\\u9fa5A-Za-z0-9*#+ ]{1,16}?)\\s*(?:打电话|电话|打个电话|$)")
                .find(goal)
            val who = m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            if (who != null) {
                return AgentAction(
                    action = "systemop", target = "call",
                    text = who, summary = "拨打 $who",
                )
            }
        }
        // 播放音乐：走自建网易云 API，直接拉流播放，不打开任何 App
        if (goal.contains("播放") || goal.contains("放首") || goal.contains("来首")) {
            val raw = goal
                .replace(Regex("^(帮我|给我|请)?(播放|放首|放一下|来首|来一首)"), "")
                .replace(Regex("(这首歌|的歌|歌曲|音乐|吧|呗)$"), "")
                .trim()
            // 纯"播放歌曲"这种没有具体歌名，用"热门"兜底
            val song = raw.ifEmpty { "热门" }.take(30)
            return AgentAction(
                action = "systemop", target = "music",
                text = song, summary = "播放 $song",
            )
        }

        // 导航：「导航去XX」「带我去XX」「去XX」
        ruleNavigation(goal)?.let { return it }

        // 沙箱终端：「在终端里跑 df -h」「执行命令 X」，或整句就是一条 shell 命令
        ruleTerminal(goal)?.let { return it }

        return null
    }

    /**
     * 导航指令识别。
     *
     * 判据要宽——用户说法的变体太多（「导航去西湖」「带我去机场」
     * 「去火车站」「开始导航」），正则穷举必然漏（nav-agent 就是因为
     * 正则漏匹配被用户骂过一轮，最后改成整条链路交给模型）。
     * 所以这里只做**高置信度**的抽取：拿到目的地就交给导航模块，
     * 目的地解析（POI 搜索）由导航模块自己做。
     */
    private fun ruleNavigation(goal: String): AgentAction? {
        if (!goal.contains("导航") && !goal.contains("带我去") && !goal.contains("带我去")) {
            // 「去XX」这种太宽（"去设置"是开 App，不是导航），只在有导航词时才认
            return null
        }
        val dest = Regex(
            "(?:导航|带我去|去|前往|到)\\s*[「\"']?([^「」\"'，。？！,.?!]{2,20})[」\"']?$",
        ).find(goal)?.groupValues?.get(1)?.trim()
        if (dest.isNullOrEmpty() || dest.length < 2) return null
        // 「导航」单独出现没目的地 → 交给模型规划
        if (dest == "导航") return null
        // 去掉粘连的动词尾巴：「导航去西湖」→ 正则吃到「去西湖」，
        // 目的地其实只有「西湖」。多带一个字，高德就搜不到 POI 了。
        val cleaned = dest.trimStart('去', '往', '到', '给')
            .trim()
        if (cleaned.length < 2) return null
        return AgentAction(
            action = "systemop", target = "navigate",
            text = cleaned, summary = "导航去 $cleaned",
        )
    }

    /**
     * 终端命令识别。两种形态：
     * 1. 显式前缀：在终端里/终端中/容器里 + 命令
     * 2. 裸命令：整句以常见 Alpine 工具名开头且不含中文
     * 判据保守——只认明确形态，模糊的（「看看存储多大」）留给模型 planner。
     */
    private fun ruleTerminal(goal: String): AgentAction? {
        val explicit = Regex(
            "(?:在)?(?:终端|沙箱|容器|shell)(?:里|中|内)?(?:执行|运行|跑|输入)?\\s*[「\"']?([^「」\"']+?)[」\"']?\\s*$"
        ).find(goal)?.groupValues?.get(1)?.trim()
        if (!explicit.isNullOrEmpty() && explicit.length >= 2 &&
            // 「打开终端」「关掉沙箱」这类是开 App 的意图，不是命令
            listOf("打开", "关闭", "关掉", "退出", "启动", "看看", "在哪").none { explicit.contains(it) }
        ) {
            return AgentAction(
                action = "systemop", target = "terminal",
                text = explicit, summary = "终端：$explicit",
            )
        }
        // 裸命令形态：纯 ASCII、首 token 是白名单里的工具
        val g = goal.trim()
        if (g.any { it.code > 127 }) return null
        val first = g.split(" ").firstOrNull() ?: return null
        if (first !in SHELL_TOOLS) return null
        return AgentAction(
            action = "systemop", target = "terminal",
            text = g, summary = "终端：$g",
        )
    }

    private fun ruleOpen(goal: String, history: List<String>): AgentAction? {
        val m = Regex("(?:打开|启动|运行|开一下)\\s*([\\u4e00-\\u9fa5A-Za-z0-9]+)").find(goal) ?: return null
        val target = m.groupValues[1].trim()
        // 目标名过长，说明正则把整句都吃进去了，宁可不认
        if (target.isEmpty() || target.length > 10) return null
        // 单步指令做完就该收手，否则下一轮规则又匹配到"打开"，无限循环
        val alreadyDone = history.any { it.startsWith("打开应用 $target") && it.contains("ok") }
        return if (alreadyDone) {
            AgentAction(action = "done", summary = "已打开 $target")
        } else {
            AgentAction(action = "open", target = target)
        }
    }

    private fun ruleKey(goal: String, history: List<String>): AgentAction? {
        val key = when {
            goal.contains("返回") || goal.contains("退回去") -> "back"
            goal.contains("桌面") || goal.contains("主页") || goal.contains("home", true) -> "home"
            goal.contains("最近任务") || goal.contains("多任务") -> "recents"
            goal.contains("通知栏") || goal.contains("下拉通知") -> "notifications"
            else -> null
        } ?: return null
        val alreadyDone = history.any { it.startsWith("按键 $key") && it.contains("ok") }
        return if (alreadyDone) {
            AgentAction(action = "done", summary = "已完成")
        } else {
            AgentAction(action = "key", target = key)
        }
    }

    private fun ruleInput(
        goal: String,
        snap: com.pilot.agent.perception.ScreenSnapshot,
        history: List<String>,
    ): AgentAction? {
        val m = Regex("(?:搜索|输入|填写|打上)\\s*[「\"']?([^「」\"']+)[」\"']?").find(goal) ?: return null
        val content = m.groupValues[1].trim()
        if (content.isEmpty()) return null
        // 端侧规则只做单步：填过一次就算完成，重复填会把内容覆盖掉
        if (history.any { it.startsWith("在 [") && it.contains("-> ok") }) {
            return AgentAction(action = "done", summary = "已输入")
        }
        val box = snap.nodes.firstOrNull { it.editable && it.enabled }
            ?: snap.nodes.firstOrNull { it.cls.contains("EditText") }
            ?: return null
        return AgentAction(action = "input", id = box.id, text = content)
    }

    private fun ruleTap(
        goal: String,
        snap: com.pilot.agent.perception.ScreenSnapshot,
        history: List<String>,
    ): AgentAction? {
        val m = Regex("(?:点击|点一下|点|按|选择)\\s*[「\"']?([^「」\"'，。]+)[」\"']?").find(goal)
            ?: return null
        val want = m.groupValues[1].trim()
        if (want.length < 1) return null
        // 单步语义：点成功过一次就收手。端侧规则没有能力验证点击结果，
        // 需要验证的交给云端规划器（它有 thought + 观察循环）。
        if (history.any { (it.startsWith("点击节点") || it.startsWith("点击坐标")) && it.contains("-> ok") }) {
            return AgentAction(action = "done", summary = "已点击「$want」")
        }
        val hit = bestMatch(want, snap.nodes.filter { it.clickable }) ?: return null
        return AgentAction(action = "tap_node", id = hit.id)
    }

    private fun ruleScroll(goal: String, snap: com.pilot.agent.perception.ScreenSnapshot): AgentAction? {
        val wantsScroll = listOf("滑", "翻", "往下", "滚", "下一页").any { goal.contains(it) }
        if (!wantsScroll) return null
        val forward = !goal.contains("上滑") && !goal.contains("往上")
        val container = snap.nodes.firstOrNull { it.scrollable } ?: return null
        return AgentAction(action = "scroll", id = container.id, target = if (forward) "forward" else "backward")
    }

    private fun bestMatch(want: String, nodes: List<UiNode>): UiNode? {
        nodes.firstOrNull { it.text.equals(want, ignoreCase = true) }?.let { return it }
        nodes.firstOrNull { it.text.contains(want, ignoreCase = true) }?.let { return it }
        nodes.firstOrNull { it.viewId.contains(want, ignoreCase = true) }?.let { return it }
        return null
    }

    companion object {
        /**
         * Alpine 沙箱里的常见命令行工具白名单。
         * IntentGate 也引用它：整句首 token 命中且无中文 → 直接判 TASK
         * （坑 #13 的变体——裸 shell 命令没有任何中文动词）。
         */
        val SHELL_TOOLS = setOf(
            "ls", "cat", "df", "du", "ps", "top", "ping", "curl", "wget",
            "uname", "whoami", "echo", "pwd", "find", "grep", "head", "tail",
            "wc", "env", "date", "free", "ifconfig", "ip", "nslookup",
            "apk", "sh", "bash", "python3", "python", "node", "git", "tar",
            "awk", "sed", "sort", "diff", "md5sum", "sha256sum",
        )

        /** 整句是不带中文的裸 shell 命令（首 token 在工具白名单里）。 */
        fun looksLikeRawShellCommand(text: String): Boolean {
            val g = text.trim()
            if (g.isEmpty() || g.any { it.code > 127 }) return false
            return g.split(" ").firstOrNull() in SHELL_TOOLS
        }
    }
}
