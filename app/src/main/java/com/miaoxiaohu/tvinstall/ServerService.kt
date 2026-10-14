package com.miaoxiaohu.tvinstall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.miaoxiaohu.tvinstall.http.MiniHttpServer
import java.net.BindException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Foreground service that owns the HTTP listener and all install/uninstall side effects. */
class ServerService : Service(), InstallHost {

    companion object {
        const val ACTION_START = "com.miaoxiaohu.tvinstall.START"
        const val ACTION_STOP = "com.miaoxiaohu.tvinstall.STOP"

        private const val TAG = "TvApkInstaller"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tvapk_service"
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }

    private lateinit var config: AppConfig
    private lateinit var repository: ApkRepository
    private var api: ApiHandler? = null
    private var server: MiniHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Uploads run on separate worker threads, so several can be in flight at once.
     * The first one to ask for the lock takes it; the last one to finish gives it back.
     */
    private val wakeLockRefs = AtomicInteger(0)

    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tvapk-io").apply { isDaemon = true }
    }

    private val pendingFlags: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private val mutablePendingFlags: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    override fun onCreate() {
        super.onCreate()
        config = AppConfig(this)
        repository = ApkRepository(this)
        InstallEvents.register(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        goForeground()
        io.execute { startServer() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        // Do NOT clobber a failure reason that startServer() just set: a port conflict is
        // the most common real problem, and without this the TV UI silently goes back to
        // "服务已停止" and the user is never told why.
        if (ServerState.status == ServerState.FAILED) {
            ServerState.notifyListeners()
        } else {
            ServerState.update(ServerState.STOPPED, 0, null)
        }
        InstallEvents.unregister()
        wakeLockRefs.set(0)
        dropWakeLock()
        io.shutdownNow()
        super.onDestroy()
    }

    // ----------------------------------------------------------------- server

    private fun startServer() {
        if (server?.isRunning == true) return
        ServerState.update(ServerState.STARTING, config.port, null)

        val handler = api ?: ApiHandler(this, config, repository, this) { message -> log(message) }
            .also { api = it }

        val created = MiniHttpServer(
            handler = { request ->
                handler.handle(request)
            },
            onLog = { message -> log(message) }
        )
        try {
            val bound = created.start(config.port)
            server = created
            ServerState.update(ServerState.RUNNING, bound, null)
            log("listening on ${NetUtil.primaryAddress() ?: "?"}:$bound")
        } catch (e: BindException) {
            ServerState.update(ServerState.FAILED, 0, "端口 ${config.port} 已被占用")
            log("bind failed on ${config.port}: ${e.message}")
            stopSelf()
        } catch (e: Throwable) {
            ServerState.update(ServerState.FAILED, 0, e.message ?: "启动失败")
            log("start failed: $e")
            stopSelf()
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
        } catch (ignored: Exception) {
            // shutting down anyway
        }
        server = null
    }

    private fun stopEverything() {
        stopServer()
        ServerState.update(ServerState.STOPPED, 0, null)
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun requestRestart() {
        io.execute {
            stopServer()
            startServer()
        }
    }

    // ------------------------------------------------------------- foreground

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(false) }
                )
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), pendingFlags
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            pendingFlags
        )

        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, NetUtil.primaryAddress() ?: "?"))
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_close,
                    getString(R.string.action_stop),
                    stop
                ).build()
            )
            .build()
    }

    // ------------------------------------------------------------ install host

    override fun requestInstall(fileName: String) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_INSTALL_APK, fileName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(intent)
        } catch (e: Throwable) {
            log("cannot launch installer for $fileName: $e")
        }
    }

    override fun requestUninstall(packageName: String) {
        // Belt and braces: even a direct API call must not be able to kill the
        // process that is answering that very call. `this.` is required because the
        // parameter shadows Context.getPackageName().
        if (packageName == this.packageName) {
            log("refusing to uninstall self: $packageName")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            requestUninstallViaUi(packageName)
            return
        }
        try {
            val pending = PendingIntent.getBroadcast(
                this,
                packageName.hashCode(),
                Intent(ApkInstaller.ACTION_UNINSTALL_RESULT).setPackage(packageName),
                mutablePendingFlags
            )
            // uninstall() returns void; the outcome arrives via the ACTION_UNINSTALL_RESULT broadcast.
            // The SDK stub declares PendingIntent as NOT extending IntentSender.
            packageManager.packageInstaller.uninstall(packageName, pending.intentSender)
        } catch (t: Throwable) {
            log("silent uninstall of $packageName failed: $t")
            requestUninstallViaUi(packageName)
        }
    }

    private fun requestUninstallViaUi(packageName: String) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_UNINSTALL_PKG, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(intent)
        } catch (e: Throwable) {
            log("cannot open uninstall UI for $packageName: $e")
        }
    }

    override fun setBusy(active: Boolean) {
        if (active) acquireWakeLock() else releaseWakeLock()
    }

    // --------------------------------------------------------------- wakelock

    private fun acquireWakeLock() {
        if (wakeLockRefs.incrementAndGet() != 1) return
        val acquired = try {
            // getSystemService(Class) only exists from API 23; this app supports 21.
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm == null) {
                false
            } else {
                val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvapk:upload")
                lock.setReferenceCounted(false)
                // Bounded: a stuck upload must not pin the CPU awake indefinitely.
                lock.acquire(WAKELOCK_TIMEOUT_MS)
                wakeLock = lock
                true
            }
        } catch (ignored: Exception) {
            // Not fatal, uploads are just a little less reliable in deep sleep.
            false
        }
        if (!acquired) wakeLockRefs.decrementAndGet()
    }

    private fun releaseWakeLock() {
        while (true) {
            val current = wakeLockRefs.get()
            if (current <= 0) return
            if (wakeLockRefs.compareAndSet(current, current - 1)) {
                if (current == 1) dropWakeLock()
                return
            }
        }
    }

    private fun dropWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (ignored: Exception) {
            // already released
        }
        wakeLock = null
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }
}