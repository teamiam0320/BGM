package com.iam.bgm.app

import com.iam.bgm.core.GeoPoint
import com.iam.bgm.core.Zone
import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.hypot

/** 자동 주행 타임라인의 사건. [at] = 경로 진행률(0~1). gear = "D"/"P", say = 운전자 발화 번호. */
@Serializable
data class DriveEvent(val at: Double, val gear: String? = null, val say: Int? = null, val whenOffer: Boolean = false)

@Serializable
data class DriverTimeline(val minutes: Int, val events: List<DriveEvent>)

/** 자동 주행이 한 틱마다 요청하는 동작. 시연 콘솔 버튼과 같은 경로(위치·기어·발화 주입)로 적용된다. */
sealed interface DriveAction {
    data class Move(val p: GeoPoint) : DriveAction
    data class SetGear(val gear: String) : DriveAction
    data class Say(val line: Int) : DriveAction
}

/** 자동 주행이 판단에 쓰는 현재 상태. */
interface DriveEnv {
    /** 에이미가 말하는 중이면 다음 발화를 미룬다. */
    val voiceBusy: Boolean
    val offerPending: Boolean
    /** 이 구간에 지금 들어가도 되는가(말하는 중에 노래가 기다리는 구간에 들어가 제안을 놓치지 않게). */
    fun canEnter(zoneId: String): Boolean
}

/**
 * 실제 도로 경로(track)를 따라 차를 움직이며 타임라인 사건을 일으키는 시뮬레이터.
 * 같은 입력이면 같은 순서로 같은 동작을 낸다(시간·난수 의존 없음) — 단위 테스트로 전체 주행을 검증할 수 있다.
 */
class AutoDrive(private val track: List<GeoPoint>, timeline: DriverTimeline) {
    private val events = timeline.events.sortedBy { it.at }
    val tripMinutes = timeline.minutes
    private val cum: DoubleArray
    private val total: Double

    init {
        require(track.size >= 2) { "track needs 2+ points" }
        cum = DoubleArray(track.size)
        for (i in 1 until track.size) cum[i] = cum[i - 1] + meters(track[i - 1], track[i])
        total = cum.last()
    }

    var progress = 0.0
        private set
    private var next = 0
    val done get() = next >= events.size && progress >= 1.0

    fun positionAt(f: Double): GeoPoint {
        val target = f.coerceIn(0.0, 1.0) * total
        var i = cum.indexOfFirst { it >= target }
        if (i < 1) i = if (i == -1) cum.size - 1 else 1
        val seg = (cum[i] - cum[i - 1]).takeIf { it > 0 } ?: return track[i]
        val t = (target - cum[i - 1]) / seg
        val a = track[i - 1]; val b = track[i]
        return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
    }

    /** 진행률을 [df]만큼 밀고, 이번 틱에 적용할 동작을 돌려준다. */
    fun step(df: Double, env: DriveEnv): List<DriveAction> {
        val out = mutableListOf<DriveAction>()
        // 1) 때가 된 사건(순서대로). 발화는 에이미가 말을 마친 뒤에.
        while (next < events.size && events[next].at <= progress + 1e-9) {
            val e = events[next]
            if (e.say != null && env.voiceBusy) break
            if (e.whenOffer && !env.offerPending) {
                if (progress < e.at + 0.15) break // 제안을 조금 더 기다린다
                next++; continue // 제안이 오지 않으면 건너뛴다(시연이 멈추지 않게)
            }
            if (e.gear == "P" && env.voiceBusy) break // 마지막 말이 끝난 뒤 주차
            e.gear?.let { out += DriveAction.SetGear(it) }
            e.say?.let { out += DriveAction.Say(it) }
            next++
            if (e.say != null) break // 발화 직후 에이미 답이 시작되므로 다음 사건은 다음 틱에
        }
        // 2) 이동
        if (progress < 1.0) {
            val cand = (progress + df).coerceAtMost(1.0)
            val now = positionAt(progress)
            val p = positionAt(cand)
            val z = Zone.idOf(p)
            if (z == Zone.idOf(now) || env.canEnter(z)) {
                progress = cand
                out += DriveAction.Move(p)
            }
        }
        return out
    }

    private fun meters(a: GeoPoint, b: GeoPoint): Double {
        val k = cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot((a.lat - b.lat) * 110_540.0, (a.lng - b.lng) * 111_320.0 * k)
    }
}
