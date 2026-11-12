package io.github.minjj.keyboard.correction

import android.view.inputmethod.EditorInfo
import io.github.minjj.keyboard.touch.*
import io.github.minjj.keyboard.ui.GeometrySnapshot
import java.util.UUID

/** Process-local registration, bound again to the actual InputConnection on every input start. */
internal object PersonalizationSessionBridge {
    class Session(val fieldId: Int, val korean: Boolean, val training: Boolean,
                  val changed: () -> Unit) {
        val marker = "minjj-practice-${UUID.randomUUID()}"
        var inputKorean = korean
        var connected = false
        var layout: LayoutDescriptor? = null
        var profile = -1
        var geometry: GeometrySnapshot? = null
        var model = SpatialModel()
        var generation = -1L
        var invalid = false
        var rejectionReason = ""
        var lastOutcome = ""
        var dispatching = false
        var applyingMutation = false
        val pendingMutations = ArrayDeque<String>()
        data class Mutation(val trial: Long, val event: Int, val command: String?)
        val pendingEvents = ArrayDeque<Mutation>()
        val editTrace = PracticeEditTrace()
        var trial = 0L
        var dispatchEvent = -1
        var dispatchCommand: String? = null
        var word: PracticeWord? = null
        val taps = mutableListOf<PracticeTap>()
        val adopted = IntArray(KeyIds.LIMIT)
        var totalSaved = 0
        var recent = emptyList<PracticeTap>()
        var excluded = 0
        var completed = 0
        var exerciseIndex = 0
        var selectPage: ((Int) -> Unit)? = null
        var finishComposition: (() -> Unit)? = null
        var evidence: FloatArray? = null
            private set
        var spatialEnabled = false
            private set
        var predictionRevision = 0L
            private set
        fun prediction(values: FloatArray?, spatial: Boolean) {
            if (spatialEnabled == spatial && (evidence?.contentEquals(values ?: FloatArray(0)) ?: (values == null))) return
            evidence = values?.copyOf(); spatialEnabled = spatial; predictionRevision++
            changed()
        }

        fun begin(word: PracticeWord, stats: SpatialStatsRepository) {
            trial++; this.word = word; taps.clear(); editTrace.clear(); dispatchEvent = -1; dispatchCommand = null; invalid = false; rejectionReason = ""
            generation = stats.generation; model = stats.model
        }

        fun geometry(descriptor: LayoutDescriptor, id: Int, measured: GeometrySnapshot) {
            val previous = layout
            val languageOnly = previous != null && (previous.family xor descriptor.family) == 32 && previous.copy(family = descriptor.family) == descriptor
            if (previous != null && previous != descriptor && !languageOnly && taps.isNotEmpty()) { reject("geometry"); taps.clear(); editTrace.clear() }
            layout = descriptor; profile = id; geometry = measured
            changed()
        }

        fun reject(reason: String = "input") { if (!invalid) rejectionReason = reason; invalid = true }

        fun tap(literal: Char, output: Char, x: Float, y: Float) {
            if (taps.size >= 1024) { reject(); return }
            taps.add(PracticeTap(literal, output, x, y, profile = profile, korean = layout?.korean ?: inputKorean)); changed()
        }

        fun mutationFinished(event: Mutation?, before: String, after: String, editStart: Int? = null, editEnd: Int? = null) {
            if (word?.sentence != true || event?.trial != trial) return
            if (!editTrace.apply(before, after, event.event, event.command, editStart, editEnd)) reject("untracked-edit")
        }

        fun complete(stats: SpatialStatsRepository, editorText: String? = null): Int {
            val expected = word ?: return 0
            word = null // A repeated completion cannot adopt the same touches twice.
            finishComposition?.invoke()
            val aligned = if (!invalid && connected && generation == stats.generation && stats.ready) layout?.let { descriptor ->
                if (expected.sentence && editTrace.observed && editorText != null)
                    SentenceAlignment.editedSamples(expected, taps, descriptor.keys, editTrace, editorText)
                else {
                    val samples = if (expected.sentence) SentenceAlignment.samples(expected, taps, descriptor.keys,
                        editorText?.let { text -> runCatching { PracticeSentences.keys(text, korean) }.getOrNull() })
                        else PracticeAlignment.samples(expected, taps, descriptor.keys)
                    samples?.map { SentenceAlignment.AlignedTouch(it, -1) }
                }
            } else null
            lastOutcome = "aligned=${aligned?.size}; invalid=$invalid:$rejectionReason; connected=$connected; " +
                "generation=${generation == stats.generation}; taps=${taps.size}/${expected.keys.length}"
            val accepted = aligned?.filter { val previous = adopted[it.sample.key]++; expected.sentence || previous < 32 }.orEmpty()
            if (training && accepted.isNotEmpty()) {
                accepted.groupBy { taps.getOrNull(it.event)?.profile?.takeIf { id -> id >= 0 } ?: profile }.forEach { (id, points) ->
                    stats.record(id, generation, points.map { it.sample })
                }
                totalSaved += accepted.size
            } else if (training) excluded++
            completed++
            recent = taps.toList()
            taps.clear(); editTrace.clear(); changed()
            return if (training) accepted.size else 0
        }
    }

    private var registered: Session? = null
    fun register(session: Session) { registered?.reject(); registered = session }
    fun unregister(session: Session) {
        if (registered !== session) return
        session.reject(); session.taps.clear(); session.editTrace.clear(); session.pendingMutations.clear(); session.pendingEvents.clear(); session.word = null; session.connected = false
        session.recent = emptyList()
        session.prediction(null, false)
        session.finishComposition = null; session.selectPage = null; registered = null
    }
    fun resolve(info: EditorInfo, packageName: String): Session? = registered?.takeIf {
        info.packageName == packageName && info.fieldId == it.fieldId && info.privateImeOptions == it.marker
    }
}
