package com.miaoxiaohu.tvinstall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out for "something changed on this TV" so the TV screen and any open web page
 * refresh together after an install or uninstall.
 */
object InstallEvents {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var lastMessage: String? = null
        private set

    @Volatile
    var lastMessageAt: Long = 0L
        private set

    private var receiver: BroadcastReceiver? = null
    private var registeredContext: Context? = null

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun recordMessage(message: String) {
        lastMessage = message
        lastMessageAt = System.currentTimeMillis()
        fire()
    }

    private fun fire() {
        main.post {
            for (listener in listeners) {
                runCatching { listener() }
            }
        }
    }

    @Synchronized
    fun register(context: Context) {
        if (receiver != null) return
        val appContext = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED -> {
                        val pkg = intent.data?.schemeSpecificPart
                        recordMessage(if (pkg != null) "已安装 $pkg" else "安装完成")
                    }

                    Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                        val pkg = intent.data?.schemeSpecificPart
                        recordMessage(if (pkg != null) "已卸载 $pkg" else "卸载完成")
                    }

                    ApkInstaller.ACTION_INSTALL_RESULT -> {
                        val status = intent.getIntExtra(
                            ApkInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE
                        )
                        val detail = intent.getStringExtra(ApkInstaller.EXTRA_MESSAGE)

                        // STATUS_PENDING_USER_ACTION means PackageInstaller is refusing a
                        // silent install and asking us to show its confirmation UI. The
                        // Intent lives in Intent.EXTRA_INTENT and NOTHING shows it unless we
                        // start it ourselves - skipping this is why a session install
                        // "succeeded" and then never installed anything.
                        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            recordMessage("请在电视上确认安装")
                            confirmIntent(ctx, intent)
                            return
                        }

                        recordMessage(
                            if (status == PackageInstaller.STATUS_SUCCESS) "安装成功"
                            else "安装失败" + (detail?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
                        )
                    }

                    ApkInstaller.ACTION_UNINSTALL_RESULT -> {
                        val status = intent.getIntExtra(
                            ApkInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE
                        )
                        recordMessage(
                            if (status == PackageInstaller.STATUS_SUCCESS ||
                                status == PackageInstaller.STATUS_PENDING_USER_ACTION
                            ) "卸载完成" else "卸载失败"
                        )
                    }
                }
            }
        }

        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        val resultFilter = IntentFilter().apply {
            addAction(ApkInstaller.ACTION_INSTALL_RESULT)
            addAction(ApkInstaller.ACTION_UNINSTALL_RESULT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(r, packageFilter, Context.RECEIVER_NOT_EXPORTED)
            appContext.registerReceiver(r, resultFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(r, packageFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(r, resultFilter)
        }
        receiver = r
        registeredContext = appContext
    }

    @Synchronized
    fun unregister() {
        val r = receiver ?: return
        val ctx = registeredContext
        if (ctx != null) {
            runCatching { ctx.unregisterReceiver(r) }
        }
        receiver = null
        registeredContext = null
    }

    /** Launches the confirmation UI PackageInstaller handed us for a pending install. */
    private fun confirmIntent(ctx: Context?, result: Intent?) {
        if (ctx == null || result == null) return
        val pending: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.getParcelableExtra(Intent.EXTRA_INTENT)
        } ?: return
        try {
            pending.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(pending)
        } catch (e: Throwable) {
            // Foreground-service exemption covers this; if it ever fails, the user is
            // stuck, so say so instead of leaving a silent "waiting for confirmation".
            recordMessage("安装失败：无法弹出确认界面（$e）")
        }
    }
}