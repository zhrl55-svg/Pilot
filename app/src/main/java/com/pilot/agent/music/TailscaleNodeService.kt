package com.pilot.agent.music

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.IBinder
import com.pilot.agent.tailscale.tailscalebridge.Tailscalebridge
import org.json.JSONObject

/** Hosts tsnet in a reclaimable process so its Go runtime never bloats Pilot's main process. */
class TailscaleNodeService : Service() {
    private val binder = object : ITailscaleNode.Stub() {
        override fun start(stateDir: String, snapshot: String): String {
            Tailscalebridge.setNetworkSnapshot(defaultInterface(snapshot), snapshotInterfaces(snapshot))
            return Tailscalebridge.start("", "pilot-music", stateDir)
        }

        override fun status(snapshot: String): String {
            Tailscalebridge.setNetworkSnapshot(defaultInterface(snapshot), snapshotInterfaces(snapshot))
            return Tailscalebridge.status()
        }

        override fun get(url: String, cookie: String, timeoutMillis: Long, snapshot: String): String {
            Tailscalebridge.setNetworkSnapshot(defaultInterface(snapshot), snapshotInterfaces(snapshot))
            return Tailscalebridge.get(url, cookie, timeoutMillis)
        }

        override fun stop(): String = Tailscalebridge.stop()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        Tailscalebridge.stop()
        stopSelf()
        return false
    }

    override fun onDestroy() {
        runCatching { Tailscalebridge.stop() }
        super.onDestroy()
    }

    private fun defaultInterface(snapshot: String): String = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.interfaceName.orEmpty()
    } catch (_: Exception) { "" }

    // Snapshot already contains Android-visible interface records. Keep one JSON format for Go.
    private fun snapshotInterfaces(snapshot: String): String = JSONObject(snapshot)
        .optJSONArray("interfaces")?.toString() ?: "[]"
}
