package io.github.minjj.keyboard.touch

import io.github.minjj.keyboard.hangul.Dubeolsik
import kotlin.random.Random

/** Original sentence patterns with independent vocabulary and quantities; no user text is retained. */
class PracticeSentences(private val korean: Boolean, private val random: Random = Random.Default) {
    private val recent = ArrayDeque<String>()
    private val patternUses = IntArray(12)
    private val transitions = mutableMapOf<String, Int>()
    private val koPeople = listOf("민수", "지우", "준호", "유나", "민지", "은우", "지호", "은서", "소라", "나라", "수아", "서우")
    private val enPeople = listOf("Alex", "Jamie", "Morgan", "Taylor", "Robin", "Casey", "Jordan", "Sam", "Riley", "Chris", "Avery", "Lee")
    private val koPlaces = listOf("도서관", "공원", "학교", "사무실", "책방", "강의실", "회의실", "운동장", "정류장", "미술관", "체육관", "박물관")
    private val enPlaces = listOf("the library", "the park", "the office", "the station", "the cafe", "the studio", "the market", "the museum", "the school", "the garden", "the gym", "the kitchen")
    private val koThings = listOf("책", "사진", "연필", "그림", "꽃", "빵", "노트", "상자", "지도", "편지", "수건", "봉투", "사과", "의자", "화분", "카드")
    private val enThings = listOf("books", "photos", "pencils", "maps", "flowers", "notebooks", "boxes", "letters", "apples", "chairs", "cards", "tickets", "bottles", "cups", "bags", "posters")
    private val koTimes = listOf("오늘", "내일", "모레", "이번 주", "다음 주", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")
    private val enTimes = listOf("today", "tomorrow", "on Monday", "on Tuesday", "on Wednesday", "on Thursday", "on Friday", "on Saturday", "on Sunday", "next week", "this week", "this evening")

    fun next(numberRow: Boolean, count: (Int) -> Float = { 0f }): PracticeWord {
        val least = patternUses.min()
        val pattern = patternUses.indices.filter { patternUses[it] == least }.random(random)
        val candidates = List(48) { make(pattern, numberRow) }.distinct().filter { it !in recent }
        val chosen = candidates.map { text -> PracticeWord(text, keys(text, korean), sentence = true) }
            .maxByOrNull { phrase ->
                val pairs = phrase.keys.zipWithNext().map { "${it.first}${it.second}" }.toSet()
                pairs.sumOf { 1.0 / (1 + transitions.getOrDefault(it, 0)) } / pairs.size.coerceAtLeast(1) +
                    phrase.keys.mapNotNull(KeyIds::fromToken).toSet().sumOf { (30f - count(it)).coerceIn(0f, 30f).toDouble() } /
                    (phrase.keys.length * 300) + random.nextDouble() * .25
            } ?: make(pattern, numberRow).let { PracticeWord(it, keys(it, korean), sentence = true) }
        patternUses[pattern]++
        recent.addLast(chosen.text)
        while (recent.size > 256) recent.removeFirst()
        chosen.keys.zipWithNext().forEach { val pair = "${it.first}${it.second}"; transitions[pair] = transitions.getOrDefault(pair, 0) + 1 }
        return chosen
    }

    private fun make(pattern: Int, digits: Boolean): String {
        fun <T> pick(values: List<T>) = values.random(random)
        val person = pick(if (korean) koPeople else enPeople)
        val place = pick(if (korean) koPlaces else enPlaces)
        val thing = pick(if (korean) koThings else enThings)
        val time = pick(if (korean) koTimes else enTimes)
        val n = if (digits) random.nextInt(1, 100).toString() else pick(if (korean) listOf("두", "세", "네", "다섯", "여섯") else listOf("two", "three", "four", "five", "six"))
        val hour = if (digits) random.nextInt(1, 13).toString() else pick(if (korean) listOf("두", "세", "네", "다섯", "여섯") else listOf("two", "three", "four", "five", "six"))
        if (korean) return when (pattern) {
            0 -> "$time ${hour}시에 $place 앞에서 ${person}와 만나요"
            1 -> "${person}야 오늘 $thing ${n}개를 함께 준비해 주세요"
            2 -> "$time $place 근처에서 ${n}분 정도 천천히 걸어요"
            3 -> "$thing ${n}개를 챙겨서 ${hour}시까지 $place 쪽으로 와 주세요"
            4 -> "${person}와 함께 ${place}에 가는 길을 ${hour}시에 다시 알아봐요"
            5 -> "$time 읽을 $thing ${n}개를 ${place}에 놓아 주세요"
            6 -> "${hour}시에 ${person}를 만나서 다음 계획을 정해요"
            7 -> "${place}에 도착하면 ${hour}시 전에 ${person}에게 연락해 주세요"
            8 -> "$time 필요한 $thing ${n}개를 꼼꼼히 확인해요"
            9 -> "${person}가 추천한 $thing ${n}개를 $time 살펴볼게요"
            10 -> "$time $place 근처에서 ${person}와 ${n}분 정도 쉬어 가요"
            else -> "$thing ${n}개를 정리한 뒤 ${person}와 함께 마무리해요"
        }
        return when (pattern) {
            0 -> "Meet $person at $place at $hour $time"
            1 -> "Please bring $n $thing to $place $time"
            2 -> "$person will walk for $n minutes near $place $time"
            3 -> "Leave $n $thing at $place before $hour $time"
            4 -> "We can plan a $n day trip with $person at $place $time"
            5 -> "Please check the $n $thing before we leave $place"
            6 -> "Ask $person to meet us at $hour $time"
            7 -> "Send $person a message by $hour when you reach $place $time"
            8 -> "We need $n $thing for the project with $person"
            9 -> "$person suggested visiting $place at $hour $time"
            10 -> "Take a $n minute break with $person at $place $time"
            else -> "Sort the $n $thing with $person after lunch $time"
        }
    }

    companion object {
        val space = KeyIds.token(checkNotNull(KeyIds.function("SPACE")))
        fun keys(text: String, korean: Boolean): String = buildString {
            for (c in text) when {
                c == ' ' -> append(space)
                c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' -> append(c)
                korean -> append(Dubeolsik.keysFor(c.toString()) ?: (PHYSICAL_LETTERS + "QWERTOP").firstOrNull { Dubeolsik.map(it) == c }?.toString()
                    ?: mapOf('ㅘ' to "hk", 'ㅙ' to "ho", 'ㅚ' to "hl", 'ㅝ' to "nj", 'ㅞ' to "np", 'ㅟ' to "nl", 'ㅢ' to "ml",
                        'ㄳ' to "rt", 'ㄵ' to "sw", 'ㄶ' to "sg", 'ㄺ' to "fr", 'ㄻ' to "fa", 'ㄼ' to "fq", 'ㄽ' to "ft", 'ㄾ' to "fx", 'ㄿ' to "fv", 'ㅀ' to "fg", 'ㅄ' to "qt")[c]
                    ?: error("Unsupported practice character"))
                else -> error("Unsupported practice character")
            }
        }
    }
}
