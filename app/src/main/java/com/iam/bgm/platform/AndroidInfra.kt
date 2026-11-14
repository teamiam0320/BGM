package com.iam.bgm.platform

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.iam.bgm.app.MusicPort
import com.iam.bgm.app.StorePort
import com.iam.bgm.app.TimePort
import com.iam.bgm.core.BgmCatalog
import com.iam.bgm.core.ContentPolicy
import com.iam.bgm.core.DiaryEntry
import com.iam.bgm.core.Dialogue
import com.iam.bgm.core.EmotionLexicon
import com.iam.bgm.core.MusicLetter
import com.iam.bgm.core.ThemeVector
import com.iam.bgm.core.Zone
import com.iam.bgm.core.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

val BgmJson = Json { ignoreUnknownKeys = true; prettyPrint = false }

/** assets 폴더의 JSON 데이터 로더. 도메인 수치·문구는 전부 여기서 들어온다. */
class AssetData(context: Context) {
    private val assets = context.assets
    private fun text(name: String) = assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    val lexicon: EmotionLexicon = BgmJson.decodeFromString(text("emotion_lexicon.json"))
    val catalog: BgmCatalog = BgmJson.decodeFromString(text("bgm_catalog.json"))
    val dialogue: Dialogue = BgmJson.decodeFromString(text("dialogue.json"))
    val policy: ContentPolicy = BgmJson.decodeFromString(text("content_policy.json"))
    val scenario: DemoScenario = BgmJson.decodeFromString(text("demo_scenario.json"))
}

@Serializable
data class RoutePoint(val id: String, val label: String, val lat: Double, val lng: Double, val privateZone: Boolean) {
    val point get() = GeoPoint(lat, lng)
}

@Serializable
data class DemoDriver(val id: String, val label: String, val startHour: Int, val startMinute: Int, val lines: List<String>)

@Serializable
data class SeedLetter(
    val id: String, val authorId: String, val zoneLat: Double, val zoneLng: Double,
    val trackId: String, val message: String, val createdDaysAgo: Int, val themes: Map<String, Double>,
)

@Serializable
data class Landmark(val label: String, val lat: Double, val lng: Double) {
    val point get() = GeoPoint(lat, lng)
}

/** 시연용 단순화 지도 데이터. river = 한강 중심선 [lat, lng] 목록. */
@Serializable
data class MapData(val river: List<List<Double>> = emptyList(), val landmarks: List<Landmark> = emptyList())

@Serializable
data class DemoScenario(
    val version: Int,
    val route: List<RoutePoint>,
    val drivers: List<DemoDriver>,
    val seedLetters: List<SeedLetter>,
    val map: MapData = MapData(),
) {
    val privateZones: Set<String> get() = route.filter { it.privateZone }.map { Zone.idOf(it.point) }.toSet()

    fun seedAsLetters(nowMillis: Long): List<MusicLetter> = seedLetters.map {
        MusicLetter(
            id = it.id, authorId = it.authorId, zoneId = Zone.idOf(GeoPoint(it.zoneLat, it.zoneLng)),
            trackId = it.trackId, message = it.message, themes = ThemeVector(it.themes),
            createdAtMillis = nowMillis - it.createdDaysAgo * 86_400_000L,
        )
    }
}

/** 앱 번들 음원(res/raw) 재생기. 트랙 id = raw 리소스 이름. */
class RawMusicPlayer(private val context: Context) : MusicPort {
    private var player: MediaPlayer? = null
    override var currentTrackId: String? = null; private set

    override fun play(trackId: String) {
        if (trackId == currentTrackId && player?.isPlaying == true) return
        stop()
        val res = context.resources.getIdentifier(trackId, "raw", context.packageName)
        if (res == 0) { Log.w("BGM/Music", "unknown track $trackId"); return }
        player = MediaPlayer.create(context, res)?.apply { isLooping = true; setVolume(0.6f, 0.6f); start() }
        currentTrackId = trackId
    }

    override fun stop() {
        player?.run { try { stop() } catch (_: Exception) {}; release() }
        player = null
        currentTrackId = null
    }
}

/**
 * 시나리오 시계. 시연 모드에서는 콘솔이 시각을 정하고(운전자 출발 시각) 경로 한 구간마다 시간을 진행시킨다.
 * 같은 조작이면 같은 시각이 나와 시연이 재현 가능하다.
 */
class ScenarioClock(private val zone: ZoneId = ZoneId.of("Asia/Seoul")) : TimePort {
    private var manual: ZonedDateTime? = null

    fun set(dayOffset: Long, hour: Int, minute: Int) {
        val today = ZonedDateTime.now(zone).toLocalDate().plusDays(dayOffset)
        manual = today.atTime(hour, minute).atZone(zone)
    }

    fun advanceMinutes(m: Long) { manual = manual?.plusMinutes(m) }

    private fun now(): ZonedDateTime = manual ?: ZonedDateTime.now(zone)
    override fun nowMillis() = now().toInstant().toEpochMilli()
    override fun hourOfDay() = now().hour
    override fun minuteOfHour() = now().minute
    override fun dateLabel(): String = now().format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN))

    fun label(): String = now().format(DateTimeFormatter.ofPattern("M/d HH:mm", Locale.KOREAN))
}

/** 기기 내 JSON 파일 저장소. 상용 버전은 편지 공유를 위해 서버 저장소로 교체한다. */
class FileStore(context: Context) : StorePort {
    private val dir = File(context.filesDir, "bgm").apply { mkdirs() }
    private val lettersFile = File(dir, "letters.json")
    private val diariesFile = File(dir, "diaries.json")
    private val receivedFile = File(dir, "received.json")
    private val receivedSer = MapSerializer(String.serializer(), SetSerializer(String.serializer()))

    val isEmpty get() = !lettersFile.exists()

    override fun letters(): List<MusicLetter> = read(lettersFile) { BgmJson.decodeFromString(ListSerializer(MusicLetter.serializer()), it) } ?: emptyList()
    override fun saveLetters(letters: List<MusicLetter>) = lettersFile.writeText(BgmJson.encodeToString(ListSerializer(MusicLetter.serializer()), letters))
    override fun diaries(): List<DiaryEntry> = read(diariesFile) { BgmJson.decodeFromString(ListSerializer(DiaryEntry.serializer()), it) } ?: emptyList()
    override fun saveDiaries(diaries: List<DiaryEntry>) = diariesFile.writeText(BgmJson.encodeToString(ListSerializer(DiaryEntry.serializer()), diaries))
    override fun received(userId: String): Set<String> = receivedAll()[userId] ?: emptySet()
    override fun saveReceived(userId: String, ids: Set<String>) =
        receivedFile.writeText(BgmJson.encodeToString(receivedSer, receivedAll() + (userId to ids)))

    fun reset() { lettersFile.delete(); diariesFile.delete(); receivedFile.delete() }

    private fun receivedAll(): Map<String, Set<String>> = read(receivedFile) { BgmJson.decodeFromString(receivedSer, it) } ?: emptyMap()

    private fun <T> read(f: File, parse: (String) -> T): T? =
        if (!f.exists()) null else try { parse(f.readText()) } catch (e: Exception) { Log.w("BGM/Store", "corrupt ${f.name}", e); null }
}

fun epochNow(): Long = Instant.now().toEpochMilli()
