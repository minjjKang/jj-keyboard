package io.github.minjj.keyboard.clipboard

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class ClipEntry(val id: String, val text: String?, val mime: String, val created: Long,
    val pinned: Boolean = false, val hash: String = "") {
    val image get() = text == null
}

/** Explicit clipboard copies only. Private app files, excluded from backup, no typing capture. */
class ClipboardHistory(private val context: Context,
    private val directory: File = File(context.filesDir, "clipboard"),
    private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var loaded = false
    private var lastSystemCopy = 0L
    private val state = MutableStateFlow<List<ClipEntry>>(emptyList())
    val entries = state.asStateFlow()
    private val index get() = File(directory, "history.json")

    suspend fun refresh() = transaction { prune(); save() }
    suspend fun capture(clip: ClipData?) = transaction {
        val expired = prune()
        val timestamp = clip?.description?.timestamp ?: 0L
        if (clip == null || clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true ||
            (timestamp > 0 && (timestamp == lastSystemCopy || now() - timestamp >= RETENTION_MILLIS))) {
            if (expired) save()
            return@transaction
        }
        val copiedAt = if (timestamp > 0) timestamp.coerceAtMost(now()) else now()
        for (position in 0 until minOf(clip.itemCount, 10)) {
            val item = clip.getItemAt(position)
            val text = item.text?.toString()
            if (!text.isNullOrEmpty() && text.length <= 20_000) {
                add(ClipEntry(UUID.randomUUID().toString(), text, "text/plain", copiedAt))
            } else if (item.uri?.scheme == "content") {
                val uri = item.uri
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                if (mime !in imageTypes) continue
                val data = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            if (bytes.size() + count > 5 * 1024 * 1024) return@use null
                            bytes.write(buffer, 0, count)
                        }
                        bytes.toByteArray()
                    }
                }.getOrNull() ?: continue
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 64_000_000) continue
                val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
                val duplicate = state.value.firstOrNull { it.image && it.hash == hash }
                if (duplicate != null) { add(duplicate.copy(created = copiedAt)); continue }
                val id = UUID.randomUUID().toString()
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > 512 || bounds.outHeight / inSampleSize > 512) inSampleSize *= 2
                }
                val thumbnail = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: continue
                File(directory, "$id.bin").writeBytes(data)
                File(directory, "$id.mime").writeText(mime!!)
                File(directory, "$id.png").outputStream().use { thumbnail.compress(Bitmap.CompressFormat.PNG, 90, it) }
                thumbnail.recycle()
                add(ClipEntry(id, null, mime, copiedAt, hash = hash))
                if (state.value.none { it.id == id }) removeFiles(id)
            }
        }
        if (timestamp > 0) lastSystemCopy = timestamp
        save()
    }

    suspend fun pin(id: String) = transaction {
        state.value = state.value.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }; save()
    }
    suspend fun setPinned(ids: Set<String>, pinned: Boolean) = transaction {
        state.value = state.value.map { if (it.id in ids) it.copy(pinned = pinned) else it }; save()
    }
    suspend fun delete(id: String) = delete(setOf(id))
    suspend fun delete(ids: Set<String>) = transaction {
        state.value.filter { it.id in ids }.forEach { removeFiles(it.id) }
        state.value = state.value.filterNot { it.id in ids }; save()
    }
    suspend fun clearUnpinned() = transaction {
        state.value.filterNot { it.pinned }.forEach { removeFiles(it.id) }
        state.value = state.value.filter { it.pinned }; save()
    }

    fun imageUri(id: String): Uri = Uri.parse("content://${context.packageName}.clips/image/$id")
    fun imageFile(id: String): File? = if (validId(id)) File(directory, "$id.bin").takeIf { it.isFile } else null
    fun thumbnailFile(id: String): File? = if (validId(id)) File(directory, "$id.png").takeIf { it.isFile } else null
    fun imageMime(id: String): String? = if (validId(id)) runCatching { File(directory, "$id.mime").readText() }.getOrNull()?.takeIf { it in imageTypes } else null

    private fun add(entry: ClipEntry) {
        val duplicate = state.value.firstOrNull { if (entry.image) it.id == entry.id else it.text == entry.text }
        var items = state.value.filterNot { it.id == duplicate?.id }
        if (items.size >= 50) {
            val evicted = items.filterNot { it.pinned }.minByOrNull { it.created } ?: return
            items = items.filterNot { it.id == evicted.id }; removeFiles(evicted.id)
        }
        state.value = (listOf(duplicate?.copy(created = entry.created) ?: entry) + items).sortedByDescending { it.created }
    }
    private fun prune(): Boolean {
        val expired = state.value.filter { !it.pinned && now() - it.created >= RETENTION_MILLIS }
        expired.forEach { removeFiles(it.id) }
        state.value = state.value.filterNot { it in expired }
        return expired.isNotEmpty()
    }
    private fun removeFiles(id: String) {
        if (validId(id)) listOf("bin", "mime", "png").forEach { File(directory, "$id.$it").delete() }
    }
    private fun validId(id: String) = id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    private suspend fun transaction(block: () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.mkdirs()
            if (!loaded) {
                val array = runCatching {
                    val saved = index.readText()
                    if (saved.startsWith('[')) JSONArray(saved) else JSONObject(saved).let {
                        lastSystemCopy = it.optLong("lastSystemCopy"); it.optJSONArray("items") ?: JSONArray()
                    }
                }.getOrElse { JSONArray() }
                state.value = (0 until array.length()).mapNotNull { i -> runCatching {
                    val value = array.getJSONObject(i)
                    ClipEntry(value.getString("id"), if (value.isNull("text")) null else value.getString("text"),
                        value.getString("mime"), value.getLong("created"), value.optBoolean("pinned"), value.optString("hash"))
                }.getOrNull() }.filter { validId(it.id) && (!it.image || imageFile(it.id) != null) }.take(50)
                loaded = true
            }
            block()
        }
    }
    private fun save() {
        val array = JSONArray()
        state.value.forEach { entry -> array.put(JSONObject().apply {
            put("id", entry.id); put("text", entry.text ?: JSONObject.NULL); put("mime", entry.mime)
            put("created", entry.created); put("pinned", entry.pinned); put("hash", entry.hash)
        }) }
        val pending = File(directory, "history.json.pending")
        try {
            val document = JSONObject().put("lastSystemCopy", lastSystemCopy).put("items", array)
            FileOutputStream(pending).use { it.write(document.toString().toByteArray()); it.fd.sync() }
            Files.move(pending.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { pending.delete() }
    }
    companion object {
        private const val RETENTION_MILLIS = 60 * 60 * 1000L
        private val imageTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        @android.annotation.SuppressLint("StaticFieldLeak") // get() retains applicationContext only.
        @Volatile private var instance: ClipboardHistory? = null
        fun get(context: Context): ClipboardHistory = instance ?: synchronized(this) {
            instance ?: ClipboardHistory(context.applicationContext).also { instance = it }
        }
    }
}
