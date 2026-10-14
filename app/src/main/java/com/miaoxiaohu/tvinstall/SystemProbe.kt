package com.miaoxiaohu.tvinstall

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Host facts that decide whether a prompt-free install is possible on this TV.
 *
 * The firmware's "是否安装此应用？" page is drawn by the system package installer, not by us,
 * so an ordinary app cannot dismiss it. PackageInstaller says so explicitly: without a
 * privilege it answers STATUS_PENDING_USER_ACTION and hands back a confirmation Intent
 * (see InstallEvents) precisely because it is not allowed to install silently.
 *
 * The only two routes that skip the page:
 *   1. hold android.permission.INSTALL_PACKAGES  -> signature|privileged, so we must already
 *      live in /system/priv-app signed with the platform key
 *   2. own a root shell -> "pm install" as uid 0 never draws the page at all
 *
 * This probe reports which of them is actually open on the current device, instead of guessing.
 */
object SystemProbe {

    /** Hidden in the SDK, so it cannot be referenced as Manifest.permission.INSTALL_PACKAGES. */
    private const val INSTALL_PACKAGES = "android.permission.INSTALL_PACKAGES"

    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/magisk/.core/bin/su",
        "/debug_ramdisk/su"
    )

    @Volatile
    private var suVerdict: Boolean? = null

    /**
     * INSTALL_PACKAGES cannot be granted at runtime (pm grant refuses signature|privileged),
     * so this is a statement of fact about how we were installed, not a fixable permission.
     */
    fun hasSilentInstallPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.checkPermission(INSTALL_PACKAGES, android.os.Process.myPid(), android.os.Process.myUid()) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

    /** A su binary on disk is only a hint; [canRunSu] is the decisive test. */
    fun suBinaryPresent(): Boolean = SU_PATHS.any { File(it).exists() }

    /**
     * Runs "su -c id" and looks for uid=0. On a rooted TV the root manager may show one prompt
     * the first time and remember the grant afterwards. Cached because a root prompt that
     * blocks would otherwise hang every /api/info poll.
     */
    @Synchronized
    fun canRunSu(): Boolean {
        suVerdict?.let { return it }
        val verdict = try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = p.waitFor(4, TimeUnit.SECONDS)
            val out = if (finished) p.inputStream.bufferedReader().use { it.readText() } else ""
            p.destroyForcibly()
            finished && out.contains("uid=0")
        } catch (t: Throwable) {
            false
        }
        suVerdict = verdict
        return verdict
    }

    /** True when an install can complete without the firmware confirmation page. */
    fun canInstallSilently(context: Context): Boolean =
        canRunSu() || hasSilentInstallPermission(context)

    fun toJson(context: Context): JSONObject = JSONObject()
        .put("androidRelease", Build.VERSION.RELEASE ?: JSONObject.NULL)
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("manufacturer", Build.MANUFACTURER ?: JSONObject.NULL)
        .put("model", Build.MODEL ?: JSONObject.NULL)
        .put("silentInstallPermission", hasSilentInstallPermission(context))
        .put("suBinary", suBinaryPresent())
        .put("suWorks", canRunSu())
        .put("canInstallSilently", canInstallSilently(context))
}
