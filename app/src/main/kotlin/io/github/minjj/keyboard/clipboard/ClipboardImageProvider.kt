package io.github.minjj.keyboard.clipboard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Non-exported provider. Only per-image read grants issued by commitContent leave the IME. */
class ClipboardImageProvider : ContentProvider() {
    override fun onCreate() = true
    private fun id(uri: Uri): String {
        if (uri.authority != "${context!!.packageName}.clips" || uri.pathSegments.size != 2 || uri.pathSegments[0] != "image") throw FileNotFoundException()
        return uri.pathSegments[1]
    }
    override fun getType(uri: Uri): String? = ClipboardHistory.get(context!!).imageMime(id(uri))
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only image")
        val file = ClipboardHistory.get(context!!).imageFile(id(uri)) ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = ClipboardHistory.get(context!!).imageFile(id(uri)) ?: throw FileNotFoundException()
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> "clipboard-image"; OpenableColumns.SIZE -> file.length(); else -> null
        } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
