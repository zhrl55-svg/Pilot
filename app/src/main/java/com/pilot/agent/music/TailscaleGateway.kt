package com.pilot.agent.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Lazy IPC client. The isolated Go process is kept for a short burst, then unbound. */
object TailscaleGateway {
    private const val TAG = "PilotTailnet"
    private const val KEEP_ALIVE_MS = 30_000L
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var remote: ITailscaleNode? = null
    private var connection: ServiceConnection? = null
    private var pending: CountDownLatch? = null
    private var releaseTask: Runnable? = null

    fun isTailnetHost(host: String): Boolean {
        val ip = runCatching { host.split('.').map(String::toInt) }.getOrNull()
        val inCgnat = ip?.size == 4 && ip[0] == 100 && ip[1] in 64..127
        return inCgnat || host.endsWith(".ts.net", ignoreCase = true)
    }

    fun start(context: Context): JSONObject = call(context) { api, snapshot ->
        api.start(context.noBackupFilesDir.resolve("tailscale-music").absolutePath, snapshot)
    }

    fun status(context: Context): JSONObject = call(context) { api, snapshot -> api.status(snapshot) }

    fun get(context: Context, url: String, cookie: String?, timeoutMillis: Long): JSONObject =
        call(context) { api, snapshot ->
            val stateDir = context.applicationContext.noBackupFilesDir.resolve("tailscale-music").absolutePath
            val started = JSONObject(api.start(stateDir, snapshot))
            if (!started.optBoolean("ok")) started.toString()
            else api.get(url, cookie.orEmpty(), timeoutMillis, snapshot)
        }

    fun stop(context: Context) {
        synchronized(lock) {
            runCatching { remote?.stop() }
            releaseLocked(context.applicationContext)
        }
    }

    private fun call(context: Context, action: (ITailscaleNode, String) -> String): JSONObject {
        val app = context.applicationContext
        return try {
            synchronized(lock) {
                val api = connectLocked(app)
                releaseTask?.let(main::removeCallbacks)
                releaseTask = Runnable { synchronized(lock) { releaseLocked(app) } }
                main.postDelayed(releaseTask!!, KEEP_ALIVE_MS)
                JSONObject(action(api, networkSnapshot(app)))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Tailnet request failed", t)
            JSONObject().put("ok", false).put("error", t.message ?: "Tailnet service unavailable")
        }
    }

    private fun connectLocked(context: Context): ITailscaleNode {
        remote?.let { return it }
        val latch = CountDownLatch(1)
        pending = latch
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = ITailscaleNode.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        connection = conn
        val intent = Intent(context, TailscaleNodeService::class.java)
        check(context.bindService(intent, conn, Context.BIND_AUTO_CREATE)) { "无法绑定 Tailnet 服务" }
        check(latch.await(10, TimeUnit.SECONDS)) { "Tailnet 服务启动超时" }
        return checkNotNull(remote) { "Tailnet Binder 未连接" }
    }

    private fun releaseLocked(context: Context) {
        releaseTask = null
        remote = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private fun networkSnapshot(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val defaultName = runCatching { cm.getLinkProperties(cm.activeNetwork)?.interfaceName.orEmpty() }.getOrDefault("")
        val array = JSONArray()
        runCatching {
            val all = NetworkInterface.getNetworkInterfaces()
            while (all.hasMoreElements()) {
                val ni = all.nextElement()
                val item = JSONObject()
                    .put("name", ni.name).put("index", ni.index).put("mtu", ni.mtu)
                    .put("up", ni.isUp).put("loopback", ni.isLoopback)
                    .put("p2p", ni.isPointToPoint).put("multicast", ni.supportsMulticast())
                    .put("broadcast", !ni.isLoopback)
                val addrs = JSONArray()
                ni.interfaceAddresses.forEach { ia ->
                    val address = ia.address ?: return@forEach
                    addrs.put("${address.hostAddress.orEmpty().substringBefore('%')}/${ia.networkPrefixLength}")
                }
                item.put("addrs", addrs)
                array.put(item)
            }
        }
        return JSONObject().put("default", defaultName).put("interfaces", array).toString()
    }
}
