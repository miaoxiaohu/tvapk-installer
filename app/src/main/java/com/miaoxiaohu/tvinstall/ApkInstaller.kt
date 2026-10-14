package com.miaoxiaohu.tvinstall

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Hands an uploaded APK to the system package installer.
 *
 * The primary path is a plain ACTION_VIEW on a content:// URI, which is what every TV
 * firmware handles: the user sees the normal install screen and the usual "allow from
 * this source" prompt. A PackageInstaller session is only used when no activity can
 * handle ACTION_VIEW at all.
 */
object ApkInstaller {

    const val ACTION_INSTALL_RESULT = "com.miaoxiaohu.tvinstall.INSTALL_RESULT"
    const val ACTION_UNINSTALL_RESULT = "com.miaoxiaohu.tvinstall.UNINSTALL_RESULT"
    const val EXTRA_STATUS = "status"
    const val EXTRA_MESSAGE = "status_message"

    sealed class Outcome {
        object Launched : Outcome()
        object NeedsPermission : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    fun uriFor(context: Context, file: File): Uri = Uri.Builder()
        .scheme("content")
        .authority(context.packageName + ".fileprovider")
        .appendPath(file.name)
        .build()

    /** Android 8+ requires our app to be allowed to install packages before it can. */
    fun hasInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun install(activity: Activity, file: File): Outcome {
        if (!file.isFile) return Outcome.Failed("文件不存在")

        val uri = uriFor(activity, file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ApkFileProvider.APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (view.resolveActivity(activity.packageManager) != null) {
            return try {
                activity.startActivity(view)
                Outcome.Launched
            } catch (e: Exception) {
                installViaSession(activity, file, e)
            }
        }
        return installViaSession(activity, file, null)
    }

    /**
     * Silent commit path. It still needs the "install unknown apps" grant for our own
     * app, but no interactive confirmation - used only when ACTION_VIEW has no handler.
     */
    private fun installViaSession(
        activity: Activity,
        file: File,
        previous: Exception?
    ): Outcome {
        if (!hasInstallPermission(activity)) return Outcome.NeedsPermission
        val installer = activity.packageManager.packageInstaller
        var sessionId = -1
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setSize(file.length())
            val pending = PendingIntent.getBroadcast(
                activity,
                file.name.hashCode(),
                Intent(ACTION_INSTALL_RESULT).setPackage(activity.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                // PackageInstaller has no commit(int); the session owns it.
                // The SDK stub declares PendingIntent as NOT extending IntentSender,
                // so the conversion has to be explicit.
                session.commit(pending.intentSender)
            }
            Outcome.Launched
        } catch (t: Throwable) {
            if (sessionId >= 0) {
                runCatching { installer.abandonSession(sessionId) }
            }
            val reason = t.message ?: previous?.message ?: "安装失败"
            Outcome.Failed(reason)
        }
    }

    /** Settings screen that grants this app permission to install packages. */
    fun permissionIntents(context: Context): List<Intent> {
        val list = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list.add(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            )
            // Some TV firmwares do not implement the per-app screen; fall back to the global list.
            list.add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        }
        list.add(Intent(Settings.ACTION_SETTINGS))
        return list
    }

    fun openPermissionSettings(activity: Activity): Boolean {
        for (intent in permissionIntents(activity)) {
            try {
                activity.startActivity(intent)
                return true
            } catch (ignored: Exception) {
                // try the next one
            }
        }
        return false
    }

    /** Firmware that exposes no installer at all - surfaced so the UI can explain why. */
    fun hasAnyInstaller(context: Context): Boolean {
        val probe = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://com.example/x.apk"), ApkFileProvider.APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return probe.resolveActivity(context.packageManager) != null ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && hasInstallPermission(context))
    }
}