package com.iam.bgm.core

import kotlin.math.floor

/** 기어 → 주행 모드. UNKNOWN은 직전 모드를 유지한다(신호 누락으로 화면이 깜빡이지 않게). */
object DriveModeReducer {
    fun reduce(previous: DriveMode, gear: Gear): DriveMode = when (gear) {
        Gear.PARK -> DriveMode.PARKED
        Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> DriveMode.DRIVING
        Gear.UNKNOWN -> previous
    }
}

/**
 * 위치를 약 500m 격자 구간으로 양자화한다. 서버에는 정확한 좌표 대신 구간 id만 보낸다(위치 보호).
 * 위도 0.0045° ≈ 500m, 경도 0.0057° ≈ 500m (위도 37.5° 기준).
 */
object Zone {
    const val LAT_STEP = 0.0045
    const val LNG_STEP = 0.0057

    fun idOf(p: GeoPoint): String = "${floor(p.lat / LAT_STEP).toLong()}:${floor(p.lng / LNG_STEP).toLong()}"

    fun centerOf(zoneId: String): GeoPoint {
        val (a, b) = zoneId.split(":").map { it.toLong() }
        return GeoPoint((a + 0.5) * LAT_STEP, (b + 0.5) * LNG_STEP)
    }

    /** 두 지점 사이 거리(미터, 근사). */
    fun metersBetween(a: GeoPoint, b: GeoPoint): Double {
        val k = Math.cos(Math.toRadians((a.lat + b.lat) / 2))
        return Math.hypot((a.lat - b.lat) * 110_540.0, (a.lng - b.lng) * 111_320.0 * k)
    }

    /** 두 구간이 같거나 맞닿아 있는지(체비셰프 거리 ≤ 1). 집·회사 주변 차단에 쓴다. */
    fun isNear(a: String, b: String): Boolean {
        val (a1, a2) = a.split(":").map { it.toLong() }
        val (b1, b2) = b.split(":").map { it.toLong() }
        return Math.abs(a1 - b1) <= 1 && Math.abs(a2 - b2) <= 1
    }
}

/** 어휘 사전 기반 온디바이스 감정·테마 분석. 서버에서는 임베딩 분류기로 교체해도 ThemeVector 계약은 같다. */
class MoodAnalyzer(private val lexicon: EmotionLexicon) {

    fun themesOf(text: String): ThemeVector {
        val norm = text.replace(" ", "")
        val w = lexicon.themes.associate { t ->
            t.id to t.keywords.count { kw -> norm.contains(kw.replace(" ", "")) }.toDouble()
        }
        return ThemeVector(w)
    }

    fun themesOf(texts: List<String>): ThemeVector = texts.fold(ThemeVector.EMPTY) { acc, t -> acc + themesOf(t) }

    fun moodOf(v: ThemeVector): MoodDef {
        val top = v.top(1).firstOrNull() ?: return lexicon.mood("neutral")
        return lexicon.mood(lexicon.themes.first { it.id == top }.mood)
    }
}

/**
 * 감정 → BGM 추천. 핵심 원칙: 부정 감정일 때 같은 분위기를 증폭하지 않는다.
 * 추천곡 valence는 항상 현재 감정 valence 이상이며, 부정 감정이면 'comfort' 곡을 우선한다.
 */
class BgmRecommender(private val catalog: BgmCatalog) {
    fun recommend(mood: MoodDef, count: Int = 3): List<Track> {
        val negative = mood.valence < 0
        val target = if (negative) mood.valence + 0.6 else mood.valence
        return catalog.tracks
            .filter { it.valence >= mood.valence }
            .sortedWith(
                compareByDescending<Track> { score(it, mood, negative, target) }.thenBy { it.id },
            )
            .take(count)
    }

    private fun score(t: Track, mood: MoodDef, negative: Boolean, target: Double): Double {
        var s = 1.0 - Math.abs(t.valence - target)
        if (mood.id in t.tags) s += 0.5
        if (negative && "comfort" in t.tags) s += 0.8
        return s
    }
}

enum class VoiceIntent { LEAVE_SONG, YES, NO, THANKS, FREE_TEXT }

/** 음성 인식 결과를 의도로 변환. 운전 중 응답은 예/아니오 수준으로 단순하게 유지한다. */
object VoiceIntentParser {
    private val yes = listOf("응", "네", "예", "좋아", "그래", "들려줘", "틀어줘", "어어", "콜", "듣고싶")
    private val no = listOf("아니", "괜찮아", "다음에", "됐어", "싫어", "나중에")
    private val thanks = listOf("고마워", "고맙", "감사")

    fun parse(text: String, awaitingYesNo: Boolean = false): VoiceIntent {
        val n = text.replace(" ", "").replace(Regex("[.,!?~]"), "")
        if (n.contains("남겨") && (n.contains("노래") || n.contains("곡") || n.contains("여기") || n.contains("이거"))) {
            return VoiceIntent.LEAVE_SONG
        }
        if (thanks.any { n.contains(it) }) return VoiceIntent.THANKS
        if (awaitingYesNo) {
            if (no.any { n.startsWith(it) || n == it }) return VoiceIntent.NO
            if (yes.any { n.startsWith(it) || n == it }) return VoiceIntent.YES
        }
        return VoiceIntent.FREE_TEXT
    }
}

sealed interface FilterResult {
    data class Ok(val message: String) : FilterResult
    data object Rejected : FilterResult
    data object Crisis : FilterResult
}

/** 남길 한마디 검수: 위기 신호는 공개하지 않고 도움 안내, 욕설·연락처·링크는 거절, 길이 제한. */
class ContentFilter(private val policy: ContentPolicy) {
    private val pii = listOf(
        Regex("\\d{2,4}[- .]?\\d{3,4}[- .]?\\d{4}"),
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        Regex("(https?://|www\\.)", RegexOption.IGNORE_CASE),
    )

    fun check(raw: String): FilterResult {
        val text = raw.trim()
        val compact = text.replace(" ", "")
        if (policy.crisisWords.any { compact.contains(it.replace(" ", "")) }) return FilterResult.Crisis
        if (text.isEmpty()) return FilterResult.Rejected
        if (policy.blockedWords.any { compact.contains(it) }) return FilterResult.Rejected
        if (pii.any { it.containsMatchIn(text) }) return FilterResult.Rejected
        return FilterResult.Ok(text.take(policy.maxMessageLength))
    }
}

/**
 * 음악 편지 매칭: 같은 구간 + 테마 유사도 ≥ 임계값 + 내가 쓴 것/이미 받은 것/만료된 것 제외.
 * 같은 조건이면 유사도가 높은 것, 그다음 최신, 그다음 id 순(결정적).
 */
class LetterMatcher(
    private val minSimilarity: Double = 0.5,
    private val maxAgeMillis: Long = 30L * 24 * 60 * 60 * 1000,
) {
    fun match(
        zoneId: String,
        myThemes: ThemeVector,
        me: String,
        letters: List<MusicLetter>,
        alreadyReceived: Set<String>,
        nowMillis: Long,
    ): MusicLetter? {
        if (myThemes.isEmpty) return null
        return letters.asSequence()
            .filter { it.zoneId == zoneId && it.authorId != me && it.id !in alreadyReceived }
            .filter { nowMillis - it.createdAtMillis in 0..maxAgeMillis }
            .map { it to it.themes.cosine(myThemes) }
            .filter { it.second >= minSimilarity }
            .sortedWith(
                compareByDescending<Pair<MusicLetter, Double>> { it.second }
                    .thenByDescending { it.first.createdAtMillis }
                    .thenBy { it.first.id },
            )
            .firstOrNull()?.first
    }

    /**
     * 차 위치 기준으로 받을 수 있는 노래: 같은 구간이거나, 구간 중심이 반경 안이면 후보.
     * 위치는 구간 단위로만 저장되므로 거리는 구간 중심으로 잰다(같은 구간이면 반경과 무관하게 후보).
     */
    fun matchNear(
        position: GeoPoint,
        radiusMeters: Double,
        myThemes: ThemeVector,
        me: String,
        letters: List<MusicLetter>,
        alreadyReceived: Set<String>,
        nowMillis: Long,
    ): MusicLetter? {
        val here = Zone.idOf(position)
        val zones = letters.map { it.zoneId }.distinct()
            .filter { it == here || Zone.metersBetween(position, Zone.centerOf(it)) <= radiusMeters }
        return zones.mapNotNull { z -> match(z, myThemes, me, letters, alreadyReceived, nowMillis) }
            .maxWithOrNull(compareBy<MusicLetter> { it.themes.cosine(myThemes) }.thenBy { it.createdAtMillis }.thenByDescending { it.id })
    }

    /**
     * 주행 중 '지금 반경 안의 노래': 같은 구간이거나 구간 중심이 반경 안인, 나에게 아직 닿지 않은 다른 사람의 노래.
     * 결 매칭과 무관하게 보여 준다(제안은 [matchNear]가 따로 고른다). 가까운 순, 같으면 최신·id 순.
     * 거리는 구간 중심까지(위치는 구간 단위로만 저장되므로).
     */
    fun inRange(
        position: GeoPoint,
        radiusMeters: Double,
        me: String,
        letters: List<MusicLetter>,
        alreadyReceived: Set<String>,
        nowMillis: Long,
    ): List<Pair<MusicLetter, Double>> {
        val here = Zone.idOf(position)
        return letters.asSequence()
            .filter { it.authorId != me && it.id !in alreadyReceived }
            .filter { nowMillis - it.createdAtMillis in 0..maxAgeMillis }
            .map { it to Zone.metersBetween(position, Zone.centerOf(it.zoneId)) }
            .filter { it.first.zoneId == here || it.second <= radiusMeters }
            .sortedWith(compareBy<Pair<MusicLetter, Double>> { it.second }.thenByDescending { it.first.createdAtMillis }.thenBy { it.first.id })
            .toList()
    }

    /**
     * 지도 표시용: 구간별로 나에게 아직 닿지 않은 다른 사람의 노래 수. 곡명·한마디는 노출하지 않는다
     * (곡 정보는 그 길을 지나 수신 반경에 들어왔을 때만 공개된다 — [inRange]). 만료 기준은 [match]와 같다.
     */
    fun waitingCountsByZone(
        me: String,
        letters: List<MusicLetter>,
        alreadyReceived: Set<String>,
        nowMillis: Long,
    ): Map<String, Int> = letters.asSequence()
        .filter { it.authorId != me && it.id !in alreadyReceived }
        .filter { nowMillis - it.createdAtMillis in 0..maxAgeMillis }
        .groupingBy { it.zoneId }.eachCount()

    /** 집·회사(사적 구간) 근처에서는 남기기를 막는다. */
    fun canLeaveAt(zoneId: String, privateZones: Collection<String>): Boolean =
        privateZones.none { Zone.isNear(it, zoneId) }
}

/**
 * 운전 중 말 걸기 게이트: 다음 회전 지점이 가까우면(복잡한 구간) 제안을 미룬다.
 * distanceToNextTurnM이 null이면 길 안내가 없는 상태로 보고 허용한다.
 */
object SpeechGate {
    const val DEFER_WITHIN_METERS = 300

    fun canOffer(distanceToNextTurnM: Int?, ttsBusy: Boolean): Boolean {
        if (ttsBusy) return false
        if (distanceToNextTurnM != null && distanceToNextTurnM in 0 until DEFER_WITHIN_METERS) return false
        return true
    }
}
