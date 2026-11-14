package com.iam.bgm.core

/**
 * 에이미 대화 정책 + 온디바이스 일기 작성기.
 * 운전 중 답변은 공감 1문장 + 질문 1문장(최대 2문장)으로 제한한다.
 * 상용 버전에서는 ami 백엔드(LLM)로 교체하되, 입력(Utterance 목록)과 출력(DiaryEntry) 계약은 유지한다.
 */
class AmiDialogue(private val dialogue: Dialogue, private val analyzer: MoodAnalyzer) {

    fun opener(hourOfDay: Int, turnSeed: Int): String {
        val key = when (hourOfDay) {
            in 5..11 -> "morning"
            in 12..20 -> "evening"
            else -> "night"
        }
        return pick(dialogue.openers.getValue(key), turnSeed)
    }

    /** 사용자 발화에 대한 짧은 응답. turnIndex로 문장을 결정적으로 고른다(난수 없음). */
    fun reply(userText: String, turnIndex: Int): String {
        val mood = analyzer.moodOf(analyzer.themesOf(userText))
        val empathy = pick(dialogue.empathy[mood.id] ?: dialogue.empathy.getValue("neutral"), turnIndex)
        val follow = pick(dialogue.followUps, turnIndex)
        return "$empathy $follow"
    }

    fun closer(mood: MoodDef, seed: Int): String = when {
        mood.valence < 0 -> pick(dialogue.closers.negative, seed)
        mood.valence > 0.3 -> pick(dialogue.closers.positive, seed)
        else -> pick(dialogue.closers.neutral, seed)
    }

    private fun pick(list: List<String>, seed: Int) = list[Math.floorMod(seed, list.size)]
}

data class TripContext(val startHour: Int, val minutes: Int, val dateLabel: String)

class DiaryComposer(
    private val dialogue: Dialogue,
    private val analyzer: MoodAnalyzer,
    private val ami: AmiDialogue,
    private val recommender: BgmRecommender,
    private val lexicon: EmotionLexicon,
) {
    fun compose(
        id: String,
        authorId: String,
        utterances: List<Utterance>,
        trip: TripContext,
        receivedLetterIds: List<String>,
        nowMillis: Long,
    ): DiaryEntry {
        val userTexts = utterances.filter { it.speaker == USER }.map { it.text }
            .filter { VoiceIntentParser.parse(it) == VoiceIntent.FREE_TEXT }
        val themes = analyzer.themesOf(userTexts)
        val mood = analyzer.moodOf(themes)

        val period = when (trip.startHour) { in 5..11 -> "출근"; in 12..20 -> "퇴근"; else -> "귀갓" }
        val intro = dialogue.diaryIntro.replace("{period}", period).replace("{minutes}", trip.minutes.toString())

        // 감정 신호가 가장 강한 발화 최대 2개를 원문 그대로 인용한다(요약이 사실을 바꾸지 않게).
        val quotes = userTexts
            .mapIndexed { i, t -> Triple(i, t, analyzer.themesOf(t).weights.values.sum()) }
            .filter { it.third > 0 }
            .sortedWith(compareByDescending<Triple<Int, String, Double>> { it.third }.thenBy { it.first })
            .take(2)
            .sortedBy { it.first }
            .map { dialogue.diaryQuote.replace("{text}", it.second) }

        val themeLabels = themes.top(2).map { tid -> lexicon.themes.first { it.id == tid }.label }
        val feeling = if (themeLabels.isEmpty()) "오늘의 마음 날씨는 ${mood.weather}."
        else "오늘 마음에 가장 크게 남은 건 ${joinWithGwa(themeLabels)}. 마음 날씨는 ${mood.weather}."

        val paragraphs = buildList {
            add(intro)
            if (quotes.isNotEmpty()) add(quotes.joinToString(" "))
            add(feeling)
            add(ami.closer(mood, userTexts.size))
        }
        val title = "${trip.dateLabel} · ${mood.weather}"
        return DiaryEntry(
            id = id,
            authorId = authorId,
            dateLabel = trip.dateLabel,
            title = title,
            paragraphs = paragraphs,
            moodId = mood.id,
            themes = themes,
            bgmTrackIds = recommender.recommend(mood).map { it.id },
            receivedLetterIds = receivedLetterIds,
            createdAtMillis = nowMillis,
        )
    }

    companion object {
        const val USER = "user"
        const val AMI = "ami"

        /** "업무 스트레스와 피로"처럼 받침에 맞는 조사(와/과)로 잇는다. */
        fun joinWithGwa(words: List<String>): String {
            if (words.size < 2) return words.joinToString("")
            val first = words[0]
            return first + (if (hasBatchim(first)) "과 " else "와 ") + words[1]
        }

        /** "흐림이에요" / "비예요"처럼 받침에 맞는 서술격 조사를 붙인다. */
        fun withCopula(word: String): String = word + (if (hasBatchim(word)) "이에요" else "예요")

        private fun hasBatchim(word: String): Boolean {
            val c = word.lastOrNull() ?: return false
            return c in '가'..'힣' && (c - '가') % 28 != 0
        }
    }
}
