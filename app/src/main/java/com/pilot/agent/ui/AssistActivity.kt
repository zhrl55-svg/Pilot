package com.pilot.agent.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 长按 Home / 电源键唤起的入口。
 *
 * ROM 侧把 config_defaultAssistant 指到 "com.pilot.agent/.ui.AssistActivity"，
 * 系统就把系统键交给 Pilot 而不是原生 Google 助理。
 * 这里只做转发，真实界面复用 [MainActivity]，避免维护两份。
 */
class AssistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
