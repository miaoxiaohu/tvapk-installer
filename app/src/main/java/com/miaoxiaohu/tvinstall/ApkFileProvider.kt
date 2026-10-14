package com.miaoxiaohu.tvinstall

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves uploaded APKs to the system package installer.
 *
 * Android 7+ refuses `file://` URIs from other processes, so the installer needs a
 * content:// URI. androidx.core.content.FileProvider would do this, but this app
 * deliberately has zero third-party dependencies - it is ~60 lines instead.
 *
 * Only files living in the app's own `apks/` directory are reachable, and the
 * requested name is sanitised before it touches the filesystem.
 */
class ApkFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        if (uri.lastPathSegment?.endsWith(".apk", ignoreCase = true) == true) APK_MIME else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only provider")
        val ctx: Context = context ?: throw FileNotFoundException("no context")
        val name = sanitize(uri.lastPathSegment) ?: throw FileNotFoundException("bad name")
        val dir = apkDir(ctx)
        val file = File(dir, name)
        // sanitize() already strips separators, but this provider is reachable by the
        // system installer, so keep the containment check as well.
        if (!file.isFile || !file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            throw FileNotFoundException("not found: $name")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * The package installer asks the provider for the display name and size before
     * showing its confirmation screen. Returning null here makes some firmwares bail out.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val name = sanitize(uri.lastPathSegment) ?: return null
        val file = File(apkDir(ctx), name)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(if (file.isFile) file.length() else 0L)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("read-only")

    private fun apkDir(ctx: Context): File =
        (ctx.getExternalFilesDir(null) ?: ctx.filesDir).resolve(DIR_NAME).apply { mkdirs() }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val DIR_NAME = "apks"

        /** Drops any path component and exotic character, so the name can never escape [apkDir]. */
        fun sanitize(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            return cleaned.ifBlank { null }?.take(120)
        }
    }
}