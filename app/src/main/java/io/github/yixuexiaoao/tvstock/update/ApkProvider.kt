package io.github.yixuexiaoao.tvstock.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** 只把下载好的更新包提供给系统安装器，不暴露其他文件。未导出，靠临时授权访问。 */
class ApkProvider : ContentProvider() {
    private fun apk() = UpdateManager.apkFile(context!!)

    override fun onCreate() = true

    override fun getType(uri: Uri) = MIME_APK

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = apk()
        if (mode != "r" || !file.exists()) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val file = apk()
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val MIME_APK = "application/vnd.android.package-archive"
    }
}
