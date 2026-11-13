package com.iam.bgm.app

import com.iam.bgm.core.DiaryEntry
import com.iam.bgm.core.GeoPoint
import com.iam.bgm.core.Gear
import com.iam.bgm.core.MusicLetter

/*
 * 앱 계층이 플랫폼에 요구하는 계약(포트). Pleos SDK 구현과 시뮬레이션 구현이 각각 이 계약을 만족한다.
 * 컨트롤러는 이 인터페이스만 알기 때문에 JVM 단위 테스트에서 가짜 구현으로 전체 시나리오를 검증할 수 있다.
 */

/** Vehicle SDK: 기어 상태 구독, 앰비언트 라이트 색상 제어. */
interface VehiclePort {
    val label: String
    fun start(onGear: (Gear) -> Unit)
    fun setAmbientColor(rgb: Int, onResult: (Boolean) -> Unit)
    fun stop()
}

/** FusedLocation SDK: 위치 구독 + 편지가 있는 구간 근접 알림. */
interface LocationPort {
    val label: String
    fun start(onLocation: (GeoPoint) -> Unit)
    fun watchZones(centers: Map<String, GeoPoint>, radiusMeters: Float, onEnter: (String) -> Unit)
    fun stop()
}

/** SpeechToText / TextToSpeech SDK. */
interface VoicePort {
    val label: String
    val isSpeaking: Boolean
    fun speak(text: String, onDone: () -> Unit = {})
    fun listen(onFinal: (String) -> Unit)
    fun stop()
}

/** NaviHelper SDK: 목적지 도착, 다음 회전 지점까지 거리. */
interface NaviPort {
    val label: String
    fun start(onArrived: () -> Unit, onNextTurnDistance: (Int?) -> Unit)
    fun stop()
}

interface MusicPort {
    val currentTrackId: String?
    fun play(trackId: String)
    fun stop()
}

interface TimePort {
    fun nowMillis(): Long
    fun hourOfDay(): Int
    fun minuteOfHour(): Int
    fun dateLabel(): String
}

/** 저장소. 데모는 기기 내 파일, 상용은 서버(편지 공유)로 교체한다. */
interface StorePort {
    fun letters(): List<MusicLetter>
    fun saveLetters(letters: List<MusicLetter>)
    fun diaries(): List<DiaryEntry>
    fun saveDiaries(diaries: List<DiaryEntry>)
    fun received(userId: String): Set<String>
    fun saveReceived(userId: String, ids: Set<String>)
}
