package com.miaoxiaohu.tvinstall

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class ApkEntry(
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val packageName: String?,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val parseError: String?,
    val installed: Boolean,
    val installedVersion: String?
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val system: Boolean,
    val enabled: Boolean,
    val firstInstallAt: Long
)

/** Owns the pending-APK directory and answers questions about installed packages. */
class ApkRepository(context: Context) {

    private val appContext = context.applicationContext
    private val pm: PackageManager get() = appContext.packageManager

    val directory: File =
        (appContext.getExternalFilesDir(null) ?: appContext.filesDir)
            .resolve(ApkFileProvider.DIR_NAME)
            .apply { mkdirs() }

    /** Parsing an APK is slow; cache it until the file actually changes. */
    private val metaCache = ConcurrentHashMap<String, ApkMeta>()

    private data class ApkMeta(
        val size: Long,
        val modified: Long,
        val packageName: String?,
        val label: String,
        val versionName: String?,
        val versionCode: Long,
        val error: String?
    )

    fun listPending(): List<ApkEntry> =
        (directory.listFiles { file -> file.isFile && file.name.endsWith(".apk", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList())
            .map { it.toEntry() }

    fun fileFor(fileName: String): File? {
        val safe = ApkFileProvider.sanitize(fileName) ?: return null
        val file = File(directory, safe)
        return file.takeIf { it.isFile && it.canonicalPath.startsWith(directory.canonicalPath + File.separator) }
    }

    /** Picks a free filename so a second upload never silently overwrites the first. */
    fun allocateFile(rawName: String?): File {
        val sanitized = ApkFileProvider.sanitize(rawName) ?: "app.apk"
        val base = sanitized.removeSuffix(".apk").removeSuffix(".APK").ifBlank { "app" }
        var candidate = File(directory, "$base.apk")
        var suffix = 1
        while (candidate.exists() && suffix < 1000) {
            candidate = File(directory, "$base-$suffix.apk")
            suffix++
        }
        return candidate
    }

    fun delete(fileName: String): Boolean {
        val file = fileFor(fileName) ?: return false
        metaCache.remove(file.absolutePath)
        return file.delete()
    }

    fun deleteAll(): Int = listPending().count { delete(it.fileName) }

    fun installedApps(includeSystem: Boolean = false): List<InstalledApp> {
        val flags = 0
        val installed = try {
            pm.getInstalledPackages(flags)
        } catch (ignored: Exception) {
            emptyList<PackageInfo>()
        }
        return installed.mapNotNull { info ->
            // Never list ourselves: a stray uninstall call must not be able to kill
            // the process that is answering the very request that carried it.
            if (info.packageName == appContext.packageName) return@mapNotNull null
            val app = info.applicationInfo ?: return@mapNotNull null
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) return@mapNotNull null
            InstalledApp(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(app).toString() }
                    .getOrDefault(info.packageName),
                versionName = info.versionName.orEmpty(),
                versionCode = versionCodeOf(info),
                system = isSystem,
                enabled = app.enabled,
                firstInstallAt = info.firstInstallTime
            )
        }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    fun installedVersionOf(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return try {
            val info = pm.getPackageInfo(packageName, 0)
            info.versionName ?: versionCodeOf(info).toString()
        } catch (ignored: Exception) {
            null
        }
    }

    fun launchIntent(packageName: String): Intent? =
        pm.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Opens whatever system screen can remove a package.
     *
     * ACTION_DELETE on a package: URI is the obvious choice and it is what worked on
     * phones - but Android TV firmware almost never registers a handler for it, so the
     * call threw ActivityNotFoundException and the button silently did nothing.
     * The "app details" settings screen is the one every TV build does implement, and
     * it carries its own uninstall button.
     */
    fun uninstall(activity: android.app.Activity, packageName: String): Boolean {
        val uri = android.net.Uri.parse("package:$packageName")
        val candidates = listOf(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
            Intent(Intent.ACTION_DELETE, uri),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        )
        for (intent in candidates) {
            try {
                activity.startActivity(intent)
                return true
            } catch (ignored: Exception) {
                // firmware does not implement this one; try the next
            }
        }
        return false
    }

    private fun File.toEntry(): ApkEntry {
        val meta = metaFor(this)
        val installedVersion = meta.packageName?.let { installedVersionOf(it) }
        return ApkEntry(
            fileName = name,
            sizeBytes = length(),
            modifiedAt = lastModified(),
            packageName = meta.packageName,
            label = meta.label,
            versionName = meta.versionName,
            versionCode = meta.versionCode,
            parseError = meta.error,
            installed = installedVersion != null,
            installedVersion = installedVersion
        )
    }

    private fun metaFor(file: File): ApkMeta {
        val length = file.length()
        val modified = file.lastModified()
        val cached = metaCache[file.absolutePath]
        if (cached != null && cached.size == length && cached.modified == modified) return cached

        val parsed = try {
            val info = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA)
            if (info == null) {
                ApkMeta(length, modified, null, file.nameWithoutExtension, null, 0L, "无法解析，可能不是有效的 APK")
            } else {
                val app = info.applicationInfo
                // loadLabel/loadIcon need sourceDir pointing at the archive, not the installed copy.
                app?.sourceDir = file.absolutePath
                app?.publicSourceDir = file.absolutePath
                val label = app?.let {
                    runCatching { it.loadLabel(pm)?.toString() }.getOrNull()
                }?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
                ApkMeta(
                    size = length,
                    modified = modified,
                    packageName = info.packageName,
                    label = label,
                    versionName = info.versionName,
                    versionCode = versionCodeOf(info),
                    error = null
                )
            }
        } catch (t: Throwable) {
            ApkMeta(length, modified, null, file.nameWithoutExtension, null, 0L, t.message ?: "解析失败")
        }
        metaCache[file.absolutePath] = parsed
        return parsed
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
}