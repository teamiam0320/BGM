package com.iam.bgm.core

import kotlinx.serialization.Serializable

/*
 * 코어 계층: 안드로이드·Pleos SDK를 모른다. 순수 Kotlin이라 JVM 단위 테스트로 검증한다.
 * 수치·어휘·문구는 assets 폴더의 JSON 데이터에서 오며 코드에 박지 않는다.
 */

// ---------- 데이터 파일 스키마 ----------

@Serializable
data class ThemeDef(val id: String, val label: String, val mood: String, val keywords: List<String>)

@Serializable
data class MoodDef(val id: String, val label: String, val weather: String, val colorHex: String, val valence: Double)

@Serializable
data class EmotionLexicon(val version: Int, val themes: List<ThemeDef>, val moods: List<MoodDef>) {
    val themeIds: List<String> get() = themes.map { it.id }
    fun mood(id: String): MoodDef = moods.first { it.id == id }
}

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val valence: Double,
    val energy: Double,
    val tags: List<String>,
)

@Serializable
data class BgmCatalog(val version: Int, val tracks: List<Track>) {
    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }
}

@Serializable
data class Closers(val negative: List<String>, val neutral: List<String>, val positive: List<String>)

@Serializable
data class Dialogue(
    val version: Int,
    val openers: Map<String, List<String>>,
    val empathy: Map<String, List<String>>,
    val followUps: List<String>,
    val closers: Closers,
    val diaryIntro: String,
    val diaryQuote: String,
    val prompts: Map<String, String>,
) {
    fun prompt(key: String, vararg args: Pair<String, String>): String {
        var s = prompts.getValue(key)
        for ((k, v) in args) s = s.replace("{$k}", v)
        return s
    }
}

@Serializable
data class ContentPolicy(
    val version: Int,
    val maxMessageLength: Int,
    val blockedWords: List<String>,
    val crisisWords: List<String>,
    val helpLine: String,
    val pickup: PickupPolicy = PickupPolicy(),
)

/** 노래 수신 반경: 차속이 빠를수록 넓게(제안을 듣고 대답하는 동안 그 길 위에 있도록). */
@Serializable
data class PickupPolicy(val leadSeconds: Double = 15.0, val minMeters: Double = 200.0, val maxMeters: Double = 600.0) {
    fun radiusMeters(speedKmh: Double): Double = (speedKmh / 3.6 * leadSeconds).coerceIn(minMeters, maxMeters)
}

// ---------- 도메인 모델 ----------

/** 테마 축별 점수. 축 순서는 [EmotionLexicon.themeIds]가 정의하는 계약이다. */
@Serializable
data class ThemeVector(val weights: Map<String, Double>) {
    fun cosine(other: ThemeVector): Double {
        val keys = weights.keys + other.weights.keys
        var dot = 0.0
        var a = 0.0
        var b = 0.0
        for (k in keys) {
            val x = weights[k] ?: 0.0
            val y = other.weights[k] ?: 0.0
            dot += x * y; a += x * x; b += y * y
        }
        if (a == 0.0 || b == 0.0) return 0.0
        return dot / (Math.sqrt(a) * Math.sqrt(b))
    }

    val isEmpty: Boolean get() = weights.values.all { it == 0.0 }

    fun top(n: Int): List<String> =
        weights.entries.filter { it.value > 0 }.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(n).map { it.key }

    operator fun plus(o: ThemeVector): ThemeVector =
        ThemeVector((weights.keys + o.weights.keys).associateWith { (weights[it] ?: 0.0) + (o.weights[it] ?: 0.0) })

    companion object { val EMPTY = ThemeVector(emptyMap()) }
}

data class GeoPoint(val lat: Double, val lng: Double)

enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

enum class DriveMode { PARKED, DRIVING }

@Serializable
data class Utterance(val speaker: String, val text: String, val atMillis: Long)

@Serializable
data class DiaryEntry(
    val id: String,
    val authorId: String,
    val dateLabel: String,
    val title: String,
    val paragraphs: List<String>,
    val moodId: String,
    val themes: ThemeVector,
    val bgmTrackIds: List<String>,
    val receivedLetterIds: List<String>,
    val createdAtMillis: Long,
)

@Serializable
data class MusicLetter(
    val id: String,
    val authorId: String,
    val zoneId: String,
    val trackId: String,
    val message: String,
    val themes: ThemeVector,
    val createdAtMillis: Long,
    /** 이 편지를 받고 고마움을 전한 사용자 id. 작성자에게는 개수만 보인다. */
    val thankedBy: List<String> = emptyList(),
    /** 작성자가 '닿았어요' 알림을 이미 확인했는지. */
    val reachNoticeSeen: Boolean = false,
)
