package com.iam.bgm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.iam.bgm.app.BgmController
import com.iam.bgm.app.ManualLocationGate
import com.iam.bgm.app.ManualVehicleGate
import com.iam.bgm.core.Gear
import com.iam.bgm.platform.AssetData
import com.iam.bgm.platform.DemoDriver
import com.iam.bgm.platform.FileStore
import com.iam.bgm.platform.PleosLocationPort
import com.iam.bgm.platform.PleosNaviPort
import com.iam.bgm.platform.PleosVehiclePort
import com.iam.bgm.platform.PleosVoicePort
import com.iam.bgm.platform.RawMusicPlayer
import com.iam.bgm.platform.ScenarioClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 시연 콘솔 상태(심사용). 실제 차량에서는 SDK 이벤트가 같은 컨트롤러 메서드를 호출한다. */
data class ConsoleState(
    val driver: DemoDriver,
    val dayOffset: Long,
    val routeIndex: Int?,
    val nextLine: Int,
    val clockLabel: String,
)

class BgmViewModel(app: Application) : AndroidViewModel(app) {
    val data = AssetData(app)
    private val store = FileStore(app)
    private val clock = ScenarioClock()
    private val voice = PleosVoicePort(app)
    private val location = ManualLocationGate(PleosLocationPort(app))
    private val vehicle = ManualVehicleGate(PleosVehiclePort(app))
    private var counter = 0

    val controller = BgmController(
        lexicon = data.lexicon, catalog = data.catalog, dialogue = data.dialogue, policy = data.policy,
        vehicle = vehicle, location = location, voice = voice,
        navi = PleosNaviPort(app), music = RawMusicPlayer(app), time = clock, store = store,
        ids = { "L${clock.nowMillis()}-${counter++}" },
    )

    private val _console = MutableStateFlow(ConsoleState(data.scenario.drivers.first(), 0, null, 0, ""))
    val console: StateFlow<ConsoleState> = _console

    init {
        if (store.isEmpty) store.saveLetters(data.scenario.seedAsLetters(clock.nowMillis()))
        selectDriver(data.scenario.drivers.first(), dayOffset = 0)
        controller.start(_console.value.driver.id, data.scenario.privateZones)
    }

    fun selectDriver(d: DemoDriver, dayOffset: Long) {
        clock.set(dayOffset, d.startHour, d.startMinute)
        _console.value = ConsoleState(d, dayOffset, null, 0, clock.label())
        controller.switchDriver(d.id, data.scenario.privateZones)
    }

    fun gear(g: Gear) { vehicle.inject(g); refresh() }

    fun moveTo(index: Int) {
        val p = data.scenario.route[index]
        if (_console.value.routeIndex != null) clock.advanceMinutes(8)
        location.inject(p.point)
        _console.value = _console.value.copy(routeIndex = index)
        refresh()
    }

    fun sayLine(i: Int) {
        val line = _console.value.driver.lines[i]
        voice.stop() // 마이크 대기를 끊고 콘솔 문장을 실제 인식 결과처럼 주입
        controller.onUserSpeech(line)
        _console.value = _console.value.copy(nextLine = i + 1)
        refresh()
    }

    fun resetDemo() {
        vehicle.inject(Gear.PARK)
        vehicle.release()
        location.release()
        store.reset()
        store.saveLetters(data.scenario.seedAsLetters(clock.nowMillis()))
        selectDriver(data.scenario.drivers.first(), 0)
    }

    private fun refresh() { _console.value = _console.value.copy(clockLabel = clock.label()) }

    override fun onCleared() { controller.stop(); voice.release() }
}
