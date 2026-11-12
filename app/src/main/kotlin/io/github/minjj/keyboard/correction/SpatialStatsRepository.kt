package io.github.minjj.keyboard.correction

import android.content.Context
import android.util.AtomicFile
import io.github.minjj.keyboard.touch.SpatialModel
import io.github.minjj.keyboard.touch.TouchSample
import io.github.minjj.keyboard.touch.LayoutDescriptor
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun spatialDay() = System.currentTimeMillis() / 86_400_000L

/** Main-thread model with one background writer. Settings and the IME share the same instance. */
internal class SpatialStatsRepository(private val file: AtomicFile) {
    private data class Write(val generation: Long, val model: SpatialModel)
    data class State(val ready: Boolean = false, val saving: Boolean = false, val failed: Boolean = false, val revision: Long = 0)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    val ready get() = loaded
    private val writes = Channel<Write>(Channel.CONFLATED)
    private val disk = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var model = SpatialModel()
        private set
    var generation = 0L
        private set
    private var loaded = false
    private var nextIdentifier = 128

    init {
        scope.launch {
            disk.withLock {
                val saved = withContext(Dispatchers.IO) { read() }
                if (generation == 0L) model = saved
                loaded = true
                publish()
            }
            for (snapshot in writes) disk.withLock {
                if (snapshot.generation == generation) {
                    val success = withContext(Dispatchers.IO) { write(snapshot.model) }
                    if (snapshot.model === model) publish(failed = !success)
                }
            }
        }
    }

    private fun publish(saving: Boolean = false, failed: Boolean = false) {
        mutableState.value = State(loaded, saving, failed, mutableState.value.revision + 1)
    }

    fun layout(descriptor: LayoutDescriptor, current: Int? = null): Int {
        model.select(descriptor, current)?.let { return it }
        nextIdentifier = maxOf(nextIdentifier, (model.layouts.maxOfOrNull { it.id } ?: 127) + 1)
        val id = nextIdentifier++
        model = model.withLayout(id, descriptor, spatialDay())
        return id
    }

    fun record(profile: Int, generation: Long, samples: List<TouchSample>) {
        if (!loaded || generation != this.generation || samples.isEmpty()) return
        model = model.updated(profile, spatialDay(), samples)
        publish(saving = true)
        writes.trySend(Write(generation, model))
    }

    suspend fun clear(): Boolean {
        generation++
        model = SpatialModel()
        publish(saving = true)
        return scope.async {
            disk.withLock {
                val snapshot = model
                val success = withContext(Dispatchers.IO) { write(snapshot) }
                if (snapshot === model) publish(failed = !success)
                success
            }
        }.await()
    }

    suspend fun clearProfile(id: Int, keepLayout: Boolean): Boolean {
        generation++
        model = model.removed(id, keepLayout)
        publish(saving = true)
        return scope.async { disk.withLock {
            val snapshot = model
            val success = withContext(Dispatchers.IO) { write(snapshot) }
            if (snapshot === model) publish(failed = !success)
            success
        } }.await()
    }

    private fun read(): SpatialModel = try {
        file.openRead().use { input ->
            require(input.channel.size() <= 2 * 1024 * 1024)
            SpatialModel.decode(input.bufferedReader(Charsets.UTF_8).readText())
        }
    } catch (_: IOException) {
        SpatialModel()
    } catch (_: IllegalArgumentException) {
        SpatialModel()
    }

    private fun write(snapshot: SpatialModel): Boolean {
        val encoded = snapshot.encode().toByteArray(Charsets.UTF_8)
        if (encoded.size > 2 * 1024 * 1024) return false
        val stream = try { file.startWrite() } catch (_: IOException) { return false }
        return try {
            stream.write(encoded)
            file.finishWrite(stream)
            // AtomicFile logs some rename failures instead of throwing. Do not report those as saved.
            file.openRead().use { it.readBytes().contentEquals(encoded) }
        } catch (_: IOException) {
            file.failWrite(stream)
            false
        }
    }

    companion object {
        private var instance: SpatialStatsRepository? = null
        internal fun replaceForTests(value: SpatialStatsRepository?): SpatialStatsRepository? = instance.also { instance = value }
        fun get(context: Context): SpatialStatsRepository = instance
            ?: SpatialStatsRepository(AtomicFile(File(context.noBackupFilesDir, "touch-bias"))).also { instance = it }
    }
}
