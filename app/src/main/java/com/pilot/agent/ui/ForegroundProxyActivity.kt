package com.pilot.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.pilot.agent.action.BackgroundLauncher

/**
 * 透明代理 Activity：把「谁要起 Activity」这件事从调用方转交到 onResume。
 *
 * ## 两个用途
 * 1. **AlarmManager 路径**：系统代发的 PendingIntent 拉起本 Activity，
 *    此时系统已经放行（系统代发 = 用户意图），本 Activity 在 onResume 里
 *    起真正的目标 Activity —— 目标由**前台中的本进程**发起，一定放行。
 * 2. **普通前台化**：把 App 短暂拉到前台再起目标。
 *
 * ## 为什么需要静态 pending 通道
 * onResume 拿不到「要起谁」——startActivity 的参数在 onCreate 就消费掉了，
 * 而 onCreate 时进程还没前台化。用 AtomicReference 挂待办动作，
 * onResume 取走执行（agent 单线程串行，一次只挂一个）。
 */
class ForegroundProxyActivity : Activity() {

    companion object {
        private val pending = java.util.concurrent.atomic.AtomicReference<((Context) -> Boolean)?>(null)

        /** 直接把 App 拉到前台执行 [action]。 */
        fun run(context: Context, action: (Context) -> Boolean): Boolean {
            pending.set(action)
            return try {
                context.startActivity(
                    Intent(context, ForegroundProxyActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
                true
            } catch (t: Throwable) {
                pending.set(null)
                false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(null)
        setContentView(View(this).apply { visibility = View.INVISIBLE })
    }

    override fun onResume() {
        super.onResume()
        val action = pending.getAndSet(null)
        val target = intent?.getParcelableExtra<Intent>(BackgroundLauncher.EXTRA_TARGET)

        // ★ 关键：起目标之前，本 Activity 必须**已经在屏幕上**。
        //   进程状态只有在窗口真正 attach 之后才从 BOUND_FOREGROUND_SERVICE
        //   变成前台应用；立刻 finish 的话窗口还没 attach，系统照样判后台拦截。
        //   所以延迟 ~350ms（够一次 attach + 一次 relayout）再起目标。
        window.decorView.postDelayed({
            try {
                if (action != null) {
                    action(this)
                } else if (target != null) {
                    startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (_: Throwable) {
                // 目标起不来也不能把代理卡住
            }
            finish()
            overridePendingTransition(0, 0)
        }, 350)
    }
}
