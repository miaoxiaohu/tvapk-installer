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
import java.util.concurrent.TimeUnit

/**
 * Hands an uploaded APK to the system package installer, preferring the few routes that never
 * draw the firmware's confirmation page.
 *
 * Priority:
 *   1. root shell -> "pm install" as uid 0, no UI at all
 *   2. INSTALL_PACKAGES privilege -> PackageInstaller session commits silently
 *   3. plain ACTION_VIEW -> the firmware install screen, one tap on the remote
 *
 * Only the last one needs a person, so the first two are worth probing for every install.
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

        // Silent routes first. "pm install" as uid 0 draws no UI whatsoever, and a session
        // commit is silent too once we hold INSTALL_PACKAGES - PackageInstaller only answers
        // STATUS_PENDING_USER_ACTION when the caller lacks that privilege.
        if (SystemProbe.canRunSu() && installViaRoot(file)) return Outcome.Launched
        if (SystemProbe.hasSilentInstallPermission(activity)) {
            return installViaSession(activity, file, null)
        }

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
     * True silent install through a root shell. `pm install` runs in the system_server
     * install path, so it never raises STATUS_PENDING_USER_ACTION and never shows the
     * firmware's confirmation page.
     */
    private fun installViaRoot(file: File): Boolean = try {
        val p = ProcessBuilder("su", "-c", "pm install -r \"${file.absolutePath}\"")
            .redirectErrorStream(true)
            .start()
        val finished = p.waitFor(180, TimeUnit.SECONDS)
        val out = if (finished) p.inputStream.bufferedReader().use { it.readText() } else ""
        p.destroyForcibly()
        finished && out.contains("Success")
    } catch (t: Throwable) {
        false
    }

    /**
     * Session commit. Fully silent when we hold INSTALL_PACKAGES; otherwise PackageInstaller
     * hands back STATUS_PENDING_USER_ACTION and InstallEvents shows its confirmation Intent -
     * it refuses to install quietly on our behalf.
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