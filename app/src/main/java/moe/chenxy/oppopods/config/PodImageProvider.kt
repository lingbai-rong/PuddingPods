package moe.chenxy.oppopods.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import moe.chenxy.oppopods.R
import java.io.File

class PodImageProvider : ContentProvider() {
    companion object {
        const val BUILTIN_BOX_NAME = "builtin_img_box.png"
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Pod images are read-only")
        val context = context ?: return null
        val fileName = uri.lastPathSegment ?: return null

        // Settings 进程使用这个 URI 读取模块内置的 PUDDING 透明产品图。
        // 不依赖 createPackageContext，也不会拿到系统/OPPO 的模型图。
        if (fileName == BUILTIN_BOX_NAME) {
            val dir = File(context.cacheDir, "shared_pod_images").apply { mkdirs() }
            val file = File(dir, BUILTIN_BOX_NAME)
            if (!file.isFile || file.length() == 0L) {
                context.resources.openRawResource(R.drawable.img_box).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val allowedNames = PodImagePrefs.load(prefs).flatMap { earphone ->
            PodImageResource.entries.mapNotNull { resource ->
                earphone.imagePath(resource)?.let { File(it).name }
            }
        }.toSet()
        if (fileName !in allowedNames) return null
        val dir = PodImagePrefs.imageDir(context)
        val file = File(dir, fileName).canonicalFile
        if (!file.path.startsWith(dir.canonicalPath) || !file.isFile) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
