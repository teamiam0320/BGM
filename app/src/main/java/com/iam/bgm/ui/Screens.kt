package com.iam.bgm.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iam.bgm.app.Caption
import com.iam.bgm.app.Offer
import com.iam.bgm.app.UiState
import com.iam.bgm.core.DiaryEntry
import com.iam.bgm.core.DriveMode
import com.iam.bgm.core.Gear
import com.iam.bgm.core.MoodDef
import com.iam.bgm.core.Track
import com.iam.bgm.core.Zone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------- 색 토큰 (밤 드라이브) ----------------

private val Ink = Color(0xFF0A0F1C)
private val Ink2 = Color(0xFF131B2E)
private val Glass = Color(0x12FFFFFF)
private val GlassStrong = Color(0x1FFFFFFF)
private val Hair = Color(0x1AFFFFFF)
private val TextHi = Color(0xFFF2F5FB)
private val Soft = Color(0xFFAEB8CF)
private val Dim = Color(0xFF6F7A93)
private val Accent = Color(0xFF8FB7FF)
private val Violet = Color(0xFFC9A7FF)
private val Live = Color(0xFF6EE7B7)

private fun hex(h: String) = Color(0xFF000000 or h.removePrefix("#").toLong(16))

@Composable
fun BgmApp(vm: BgmViewModel) {
    val s by vm.controller.state.collectAsState()
    val c by vm.console.collectAsState()
    var consoleOpen by remember { mutableStateOf(true) }
    // 주차 일기가 있으면 화면 전체가 오늘의 감정 색으로 은은하게 물든다
    val tint by animateColorAsState(
        if (s.mode == DriveMode.PARKED) s.diaryMood?.let { hex(it.colorHex) } ?: Accent else Accent,
        tween(900), label = "tint",
    )
    Row(Modifier.fillMaxSize().background(Ink)) {
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .background(Brush.verticalGradient(listOf(Ink2, Ink)))
                .background(Brush.radialGradient(listOf(tint.copy(alpha = 0.16f), Color.Transparent), center = Offset(1400f, 0f), radius = 1300f)),
        ) {
            Column(Modifier.fillMaxSize()) {
                TopBar(s, vm, consoleOpen) { consoleOpen = !consoleOpen }
                Crossfade(targetState = s.mode == DriveMode.DRIVING, animationSpec = tween(450), label = "mode") { driving ->
                    if (driving) DrivingScreen(s, vm) else ParkedScreen(s, vm)
                }
            }
        }
        if (consoleOpen) DemoConsole(vm, s, c)
    }
}

// ---------------- 상단 바: 상태·구간·콘솔 토글 ----------------

@Composable
private fun TopBar(s: UiState, vm: BgmViewModel, consoleOpen: Boolean, onToggle: () -> Unit) {
    val zoneLabel = s.zoneId?.let { z -> vm.data.scenario.route.firstOrNull { Zone.idOf(it.point) == z }?.label }
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("BGM", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(20.dp))
        val driving = s.mode == DriveMode.DRIVING
        Pill(if (driving) "주행 중 · 음성 전용" else "주차 중", dot = if (driving) Live else Dim)
        if (zoneLabel != null) {
            Spacer(Modifier.width(12.dp))
            Pill(zoneLabel, dot = Accent)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.clip(RoundedCornerShape(50)).border(1.dp, Hair, RoundedCornerShape(50))
                .clickable(onClick = onToggle).padding(horizontal = 18.dp, vertical = 10.dp),
        ) { Text(if (consoleOpen) "시연 콘솔 닫기" else "시연 콘솔 열기", color = Soft, fontSize = 15.sp) }
    }
}

@Composable
private fun Pill(text: String, dot: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Glass).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Soft, fontSize = 17.sp, maxLines = 1)
    }
}

// ---------------- 주행 화면: 음성 전용, 최소 표시 ----------------

@Composable
private fun DrivingScreen(s: UiState, vm: BgmViewModel) {
    Column(
        Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 흘끗 보는 지도: 글자 없이 경로·내 위치·노래가 있는 구간의 빛나는 점만. 노래 내용은 음성으로만.
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF0D1426)).border(1.dp, Hair, RoundedCornerShape(28.dp))) {
            MusicMap(
                route = vm.data.scenario.route, map = vm.data.scenario.map, osm = vm.data.osm, track = vm.data.scenario.trackPoints, position = s.position, pins = pinsOf(s),
                currentZone = s.zoneId, offerZone = s.pendingOffer?.letter?.zoneId,
                detail = false, selected = null, onSelect = {}, modifier = Modifier.fillMaxSize(),
            )
            ListenBadge(s.listening, Modifier.align(Alignment.TopStart).padding(20.dp))
        }
        Spacer(Modifier.height(24.dp))
        Transcript(s.captions)
        Spacer(Modifier.height(32.dp))
        s.pendingOffer?.let { OfferBanner(it) } ?: NowPlaying(s.nowPlaying)
    }
}

/** 마지막 대화 두 줄만 크게. 운전 중 읽을 거리를 늘리지 않는다. */
@Composable
private fun Transcript(captions: List<Caption>) {
    val lastAmi = captions.lastOrNull { it.speaker == "ami" }?.text.orEmpty()
    val lastUser = captions.lastOrNull { it.speaker == "user" }?.text
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (lastUser != null) {
            Text("나", color = Dim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("“$lastUser”", color = Soft, fontSize = 24.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(20.dp))
        }
        if (lastAmi.isNotEmpty()) {
            Text("에이미", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(lastAmi, color = TextHi, fontSize = 36.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 3, lineHeight = 48.sp)
        }
    }
}

@Composable
private fun OfferBanner(o: Offer) {
    val t = rememberInfiniteTransition(label = "offer")
    val glow by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "glow")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF3A2A5C), Color(0xFF231B3A))))
            .border(2.dp, Violet.copy(alpha = glow), RoundedCornerShape(28.dp)).padding(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NoteBadge(Violet)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text("누군가 이 길에 남긴 노래", color = Violet, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(o.track.title, color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("“${o.letter.message}”", color = Soft, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.clip(RoundedCornerShape(50)).background(Violet.copy(alpha = 0.18f)).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("\"응\" 하면 재생", color = Violet, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NowPlaying(t: Track?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Glass).border(1.dp, Hair, RoundedCornerShape(28.dp)).padding(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t != null) Equalizer(Accent, 40.dp) else NoteBadge(Dim)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(if (t != null) "지금 흐르는 BGM" else "BGM", color = Dim, fontSize = 16.sp)
            Text(t?.title ?: "재생 중인 BGM 없음", color = TextHi, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("\"에이미, 이 노래 여기 남겨줘\"", color = Soft, fontSize = 19.sp)
    }
}

@Composable
private fun NoteBadge(c: Color) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text("♪", color = c, fontSize = 30.sp)
    }
}

/** 재생 중 표시. 막대 4개가 서로 다른 위상으로 오르내린다. */
@Composable
private fun Equalizer(c: Color, height: Dp) {
    val t = rememberInfiniteTransition(label = "eq")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase")
    Canvas(Modifier.size(height * 1.2f, height)) {
        val n = 4
        val gap = size.width / (n * 2f)
        for (i in 0 until n) {
            val h = size.height * (0.35f + 0.65f * ((sin(phase + i * 1.3f) + 1f) / 2f))
            drawRoundRect(c, topLeft = Offset(gap * (2 * i + 0.5f), size.height - h), size = Size(gap, h), cornerRadius = CornerRadius(gap / 2))
        }
    }
}

// ---------------- 정차 화면 ----------------

@Composable
private fun ParkedScreen(s: UiState, vm: BgmViewModel) {
    val diary = s.latestDiary
    Row(
        Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (diary == null) { HomeMap(s, vm); return@Row }
        Box(Modifier.weight(1.3f).fillMaxHeight()) {
            DiaryPanel(diary, s.diaryMood, s.helpLine)
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            run {
                s.diaryMood?.let { MoodCard(it, s.ambientApplied) }
                Card("오늘의 일기 BGM") {
                    diary.bgmTrackIds.mapNotNull { vm.data.catalog.track(it) }.forEach { t ->
                        TrackRow(t, playing = s.nowPlaying?.id == t.id, accent = Accent) { vm.controller.playFromParked(t.id) }
                    }
                }
                if (s.receivedThisTrip.isNotEmpty()) {
                    Card("오늘 받은 노래", titleColor = Violet) {
                        s.receivedThisTrip.forEach { o ->
                            TrackRow(o.track, playing = s.nowPlaying?.id == o.track.id, note = "“${o.letter.message}”", accent = Violet) { vm.controller.playFromParked(o.track.id) }
                        }
                    }
                }
            }
            if (s.myLetters.isNotEmpty()) MyLetters(s, vm)
        }
    }
}

/** 대표 기능 소개. 일기가 아직 없을 때 홈 오른쪽에 둔다. */
@Composable
private fun LetterIntro() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF33265A), Color(0xFF1C1733))))
            .border(1.dp, Violet.copy(alpha = 0.35f), RoundedCornerShape(28.dp)).padding(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NoteBadge(Violet)
            Spacer(Modifier.width(16.dp))
            Text("길 위의 음악 편지", color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text("“에이미, 이 노래 여기 남겨줘”", color = Violet, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("지금 듣는 노래와 한마디를 이 길에 남기면, 비슷한 하루를 보낸 누군가가 같은 길을 지날 때 위로로 받아요.", color = Soft, fontSize = 19.sp, lineHeight = 29.sp)
        Spacer(Modifier.height(12.dp))
        Text("위치는 500m 구간으로만 저장 · 집·회사 근처는 제외", color = Dim, fontSize = 16.sp)
    }
}

/** 지도에 찍을 구간: 다른 사람 노래가 기다리는 곳(개수만), 내가 남긴 곳, 이번 주행에 받은 곳. */
private fun pinsOf(s: UiState): List<MapPin> {
    val received = s.receivedThisTrip.map { it.letter.zoneId }.toSet()
    val zones = s.zoneLetterCounts.keys + s.myLetters.map { it.zoneId } + received
    return zones.map { z ->
        val mine = s.myLetters.filter { it.zoneId == z }
        MapPin(z, s.zoneLetterCounts[z] ?: 0, mine.size, mine.any { it.thankedBy.isNotEmpty() }, z in received)
    }
}

/** 구간 id에 가장 가까운 지명. 상세 카드 이름으로 쓰며 정확한 위치는 드러내지 않는다. */
private fun zoneName(zoneId: String, vm: BgmViewModel): String {
    val c = Zone.centerOf(zoneId)
    val named = vm.data.scenario.map.landmarks.map { it.label to it.point }
    val best = named.minByOrNull { (_, p) -> (p.lat - c.lat) * (p.lat - c.lat) + (p.lng - c.lng) * (p.lng - c.lng) }
    return best?.first ?: "이 구간"
}

@Composable
private fun ListenBadge(listening: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "listen")
    val k by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "k")
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xCC0A0F1C)).border(1.dp, Hair, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).scale(if (listening) k else 1f).clip(CircleShape).background(if (listening) Live else Accent))
        Spacer(Modifier.width(10.dp))
        Text(if (listening) "듣고 있어요" else "에이미", color = TextHi, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 주차 중 기본 화면: 음악 지도. 다른 사람 노래는 곡 수만, 내 노래는 상태까지. */
@Composable
private fun HomeMap(s: UiState, vm: BgmViewModel) {
    var selected by remember { mutableStateOf<String?>(null) }
    val pins = pinsOf(s)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Color(0xFF0D1426)).border(1.dp, Hair, RoundedCornerShape(32.dp))) {
            MusicMap(
                route = vm.data.scenario.route, map = vm.data.scenario.map, osm = vm.data.osm, track = vm.data.scenario.trackPoints, position = s.position, pins = pins,
                currentZone = s.zoneId, offerZone = null, detail = true, selected = selected,
                onSelect = { selected = it }, modifier = Modifier.fillMaxSize(),
            )
            Column(Modifier.align(Alignment.TopStart).padding(28.dp)) {
                Text("음악 지도", color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("노래가 기다리는 길 · 지나갈 때 에이미가 들려줘요", color = Soft, fontSize = 18.sp)
            }
            Row(Modifier.align(Alignment.BottomStart).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Violet))
                Text("  기다리는 노래(곡 수)", color = Soft, fontSize = 16.sp)
                Spacer(Modifier.width(20.dp))
                Box(Modifier.size(14.dp).clip(CircleShape).border(2.dp, Accent, CircleShape))
                Text("  내가 남긴 노래", color = Soft, fontSize = 16.sp)
            }
            Text(
                "기어를 D로 바꾸면 에이미가 먼저 말을 걸어요",
                color = Accent, fontSize = 17.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            )
        }
        Row(Modifier.fillMaxWidth().height(300.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val z = selected
                if (z == null) LetterIntro() else ZoneDetail(z, s, vm)
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (s.myLetters.isNotEmpty()) MyLetters(s, vm)
                PastDiaries(s.pastDiaries, vm)
            }
        }
    }
}

@Composable
private fun ZoneDetail(zoneId: String, s: UiState, vm: BgmViewModel) {
    val waiting = s.zoneLetterCounts[zoneId] ?: 0
    val mine = s.myLetters.filter { it.zoneId == zoneId }
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Glass).border(1.dp, Hair, RoundedCornerShape(28.dp)).padding(28.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("${zoneName(zoneId, vm)} 부근", color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (waiting > 0) {
            Text("이 길에서 노래 ${waiting}곡이 기다리고 있어요", color = Violet, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text("어떤 노래인지는 비슷한 하루를 보낸 날 이 길을 지나면 에이미가 들려줘요.", color = Soft, fontSize = 18.sp, lineHeight = 27.sp)
        } else if (mine.isEmpty()) {
            Text("아직 이 길에 남겨진 노래가 없어요", color = Soft, fontSize = 19.sp)
        }
        mine.forEach { l ->
            Spacer(Modifier.height(14.dp))
            val t = vm.data.catalog.track(l.trackId)
            Text("내 노래 · ${t?.title ?: l.trackId}", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("“${l.message}” · ${if (l.thankedBy.isEmpty()) "닿기를 기다리는 중" else "${l.thankedBy.size}명에게 닿았어요"}", color = Soft, fontSize = 17.sp)
        }
    }
}

@Composable
private fun DiaryPanel(diary: DiaryEntry, mood: MoodDef?, helpLine: String?) {
    val tint = mood?.let { hex(it.colorHex) } ?: Accent
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(32.dp)).background(Glass).border(1.dp, Hair, RoundedCornerShape(32.dp))
            .padding(44.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (mood != null) WeatherGlyph(mood.weather, tint, 84.dp)
            Spacer(Modifier.width(22.dp))
            Column {
                Text("오늘의 일기", color = Soft, fontSize = 18.sp)
                Text(diary.title, color = TextHi, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hair))
        Spacer(Modifier.height(28.dp))
        diary.paragraphs.forEach { p ->
            if (p.contains('“') || p.contains('"')) {
                // 사용자가 털어놓은 말은 인용으로 구분한다
                Row(Modifier.padding(bottom = 22.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(tint))
                    Spacer(Modifier.width(18.dp))
                    Text(p, color = Soft, fontSize = 24.sp, lineHeight = 38.sp)
                }
            } else {
                Text(p, color = Color(0xFFE6EAF4), fontSize = 25.sp, lineHeight = 40.sp, modifier = Modifier.padding(bottom = 22.dp))
            }
        }
        helpLine?.let {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x33FF8A75)).padding(20.dp)) {
                Text("도움이 필요할 때: $it", color = Color(0xFFFFC2B8), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MoodCard(m: MoodDef, ambient: Boolean?) {
    val c = hex(m.colorHex)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(c.copy(alpha = 0.45f), c.copy(alpha = 0.12f))))
            .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(28.dp)).padding(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("마음 날씨", color = TextHi.copy(alpha = 0.8f), fontSize = 18.sp)
            Text(m.weather, color = TextHi, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(m.label, color = TextHi.copy(alpha = 0.85f), fontSize = 21.sp)
            // 조명을 실제로 바꿨을 때만 알린다. 미지원 환경 안내는 시연 콘솔의 SDK 상태에 표시된다.
            if (ambient == true) {
                Spacer(Modifier.height(10.dp))
                Text("차 안 조명을 오늘의 색으로 바꿨어요", color = TextHi.copy(alpha = 0.75f), fontSize = 17.sp)
            }
        }
        WeatherGlyph(m.weather, Color.White, 96.dp)
    }
}

@Composable
private fun PastDiaries(diaries: List<DiaryEntry>, vm: BgmViewModel) {
    Card("지난 일기") {
        if (diaries.isEmpty()) {
            Text("아직 일기가 없어요.\n첫 드라이브를 시작해 보세요.", color = Soft, fontSize = 21.sp, lineHeight = 32.sp)
        } else {
            diaries.take(4).forEach { d ->
                val mood = runCatching { vm.data.lexicon.mood(d.moodId) }.getOrNull()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (mood != null) WeatherGlyph(mood.weather, hex(mood.colorHex), 52.dp)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.title, color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text(d.paragraphs.lastOrNull().orEmpty(), color = Dim, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun MyLetters(s: UiState, vm: BgmViewModel) {
    Card("내가 길 위에 남긴 노래") {
        s.myLetters.sortedByDescending { it.createdAtMillis }.forEach { l ->
            val t = vm.data.catalog.track(l.trackId)
            val reached = l.thankedBy.isNotEmpty()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t?.title ?: l.trackId, color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("“${l.message}”", color = Soft, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (reached) Violet.copy(alpha = 0.2f) else Glass)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(if (reached) "${l.thankedBy.size}명에게 닿았어요" else "닿기를 기다리는 중", color = if (reached) Violet else Dim, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun Card(title: String, titleColor: Color = Soft, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Glass).border(1.dp, Hair, RoundedCornerShape(28.dp)).padding(28.dp)) {
        Text(title, color = titleColor, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun TrackRow(t: Track, playing: Boolean, accent: Color, note: String? = null, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
            .background(if (playing) accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            if (playing) Equalizer(accent, 30.dp) else PlayIcon(Soft)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (playing) accent else TextHi, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(note ?: t.artist, color = Dim, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (playing) Text("재생 중", color = accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PlayIcon(c: Color) {
    Canvas(Modifier.size(44.dp)) {
        drawCircle(c.copy(alpha = 0.14f))
        val w = size.width
        val p = Path().apply {
            moveTo(w * 0.42f, w * 0.32f); lineTo(w * 0.70f, w * 0.5f); lineTo(w * 0.42f, w * 0.68f); close()
        }
        drawPath(p, c)
    }
}

// ---------------- 마음 날씨 일러스트 (외부 이미지 없이 직접 그림) ----------------

@Composable
private fun WeatherGlyph(weather: String, tint: Color, size: Dp) {
    val t = rememberInfiniteTransition(label = "wx")
    val fall by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "fall")
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension
        when (weather) {
            "맑음", "햇살" -> sun(Offset(u * 0.5f, u * 0.5f), u * 0.22f, tint, rays = true)
            "구름 조금" -> {
                sun(Offset(u * 0.62f, u * 0.36f), u * 0.16f, tint, rays = true)
                cloud(Offset(u * 0.44f, u * 0.62f), u * 0.30f, Color.White.copy(alpha = 0.92f))
            }
            "흐림" -> {
                cloud(Offset(u * 0.60f, u * 0.40f), u * 0.24f, tint.copy(alpha = 0.55f))
                cloud(Offset(u * 0.44f, u * 0.58f), u * 0.32f, tint)
            }
            "비" -> {
                cloud(Offset(u * 0.5f, u * 0.40f), u * 0.32f, tint)
                for (i in 0..2) {
                    val x = u * (0.32f + 0.18f * i)
                    val y = u * (0.62f + 0.22f * ((fall + i * 0.33f) % 1f))
                    drawLine(tint.copy(alpha = 0.9f), Offset(x, y), Offset(x - u * 0.04f, y + u * 0.10f), strokeWidth = u * 0.045f, cap = StrokeCap.Round)
                }
            }
            "눈" -> {
                cloud(Offset(u * 0.5f, u * 0.40f), u * 0.32f, tint)
                for (i in 0..2) {
                    val y = u * (0.66f + 0.2f * ((fall + i * 0.33f) % 1f))
                    drawCircle(Color.White, radius = u * 0.035f, center = Offset(u * (0.32f + 0.18f * i), y))
                }
            }
            "천둥" -> {
                cloud(Offset(u * 0.5f, u * 0.38f), u * 0.32f, tint)
                val bolt = Path().apply {
                    moveTo(u * 0.54f, u * 0.56f); lineTo(u * 0.42f, u * 0.76f); lineTo(u * 0.52f, u * 0.76f)
                    lineTo(u * 0.46f, u * 0.94f); lineTo(u * 0.64f, u * 0.70f); lineTo(u * 0.54f, u * 0.70f); close()
                }
                drawPath(bolt, Color(0xFFFFD66B))
            }
            "안개" -> for (i in 0..3) {
                val y = u * (0.30f + 0.14f * i)
                val inset = if (i % 2 == 0) 0.16f else 0.24f
                drawLine(tint.copy(alpha = 0.95f - i * 0.15f), Offset(u * inset, y), Offset(u * (1 - inset), y), strokeWidth = u * 0.06f, cap = StrokeCap.Round)
            }
            "무지개" -> {
                listOf(Color(0xFFFF8A8A), Color(0xFFFFD66B), Color(0xFF8FD3A8), Color(0xFF8FB7FF)).forEachIndexed { i, c ->
                    val r = u * (0.40f - i * 0.075f)
                    drawArc(c, 180f, 180f, false, topLeft = Offset(u * 0.5f - r, u * 0.68f - r), size = Size(r * 2, r * 2), style = Stroke(u * 0.06f, cap = StrokeCap.Round))
                }
            }
            else -> drawCircle(tint, radius = u * 0.3f)
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float, color: Color, rays: Boolean) {
    drawCircle(color.copy(alpha = 0.25f), radius = r * 1.6f, center = c)
    drawCircle(color, radius = r, center = c)
    if (rays) for (i in 0 until 8) {
        val a = i * PI / 4
        val from = Offset(c.x + cos(a).toFloat() * r * 1.45f, c.y + sin(a).toFloat() * r * 1.45f)
        val to = Offset(c.x + cos(a).toFloat() * r * 1.9f, c.y + sin(a).toFloat() * r * 1.9f)
        drawLine(color, from, to, strokeWidth = r * 0.22f, cap = StrokeCap.Round)
    }
}

/** 원 세 개 + 바닥 둥근 사각형으로 만든 구름. c는 구름 중심, w는 반폭. */
private fun DrawScope.cloud(c: Offset, w: Float, color: Color) {
    drawCircle(color, radius = w * 0.42f, center = Offset(c.x - w * 0.45f, c.y + w * 0.12f))
    drawCircle(color, radius = w * 0.58f, center = Offset(c.x, c.y - w * 0.08f))
    drawCircle(color, radius = w * 0.40f, center = Offset(c.x + w * 0.50f, c.y + w * 0.14f))
    drawRoundRect(color, topLeft = Offset(c.x - w * 0.85f, c.y + w * 0.05f), size = Size(w * 1.75f, w * 0.48f), cornerRadius = CornerRadius(w * 0.24f))
}

// ---------------- 시연 콘솔(심사용) ----------------

@Composable
private fun DemoConsole(vm: BgmViewModel, s: UiState, c: ConsoleState) {
    Column(
        Modifier.width(560.dp).fillMaxHeight().background(Color(0xFF070B14)).border(1.dp, Hair)
            .padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("시연 콘솔 (심사용)", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("실차에서는 기어·위치·음성이 Pleos SDK에서 들어옵니다. 여기서는 같은 이벤트를 수동으로 주입합니다.", color = Soft, fontSize = 15.sp)
        Text("시나리오 시각 ${c.clockLabel} · ${if (s.mode == DriveMode.DRIVING) "주행" else "주차"}", color = Accent, fontSize = 18.sp)

        if (vm.data.scenario.autoDrive != null) {
            Label("자동 주행 (실제 도로 경로)")
            val running = c.autoProgress != null
            Button(
                onClick = { if (running) vm.stopAutoDrive() else vm.startAutoDrive() },
                enabled = running || s.mode == DriveMode.PARKED,
                colors = ButtonDefaults.buttonColors(containerColor = if (running) Color(0xFF5A3A7A) else Violet, disabledContainerColor = Color(0xFF141A28)),
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (running) "■ 멈춤 · ${(c.autoProgress!! * 100).toInt()}%" else "▶ ${c.driver.label.substringBefore(" (")} 자동 주행",
                    color = if (running) Color.White else Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Text("출발(D) → 대화 → 노래 남기기·받기 → 주차(P)까지 저절로 진행돼요. 아래 버튼으로 장면별 시연도 할 수 있어요.", color = Soft, fontSize = 14.sp)
        }

        Label("1. 운전자")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.data.scenario.drivers.forEach { d ->
                Chip(d.label, selected = d.id == c.driver.id, enabled = s.mode == DriveMode.PARKED) { vm.selectDriver(d, dayOffset = c.dayOffset + 1) /* 운전자 전환 = 다음 날. 시계가 거꾸로 가면 편지 만료 판정이 깨진다 */ }
            }
        }
        Label("2. 기어 (Vehicle SDK 이벤트)")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("P 주차", selected = s.mode == DriveMode.PARKED) { vm.gear(Gear.PARK) }
            Chip("D 주행", selected = s.mode == DriveMode.DRIVING) { vm.gear(Gear.DRIVE) }
        }
        Label("3. 위치 (FusedLocation SDK 이벤트)")
        vm.data.scenario.route.forEachIndexed { i, p ->
            Chip("${i + 1}. ${p.label}${if (p.privateZone) " · 사적 구간" else ""}", selected = c.routeIndex == i) { vm.moveTo(i) }
        }
        Label("4. 운전자 발화 (SpeechToText SDK 결과)")
        c.driver.lines.forEachIndexed { i, line ->
            Chip("“$line”", selected = false, enabled = s.mode == DriveMode.DRIVING, highlight = i == c.nextLine) { vm.sayLine(i) }
        }
        Label("SDK 연결 상태")
        s.portLabels.forEach { Text("· $it", color = Soft, fontSize = 14.sp) }
        if (s.ambientApplied == false) Text("· 앰비언트 라이트: 이 환경에서는 미지원(주차 시 색 변경 생략)", color = Soft, fontSize = 14.sp)
        Text("· 현재 구간 ${s.zoneId ?: "-"}", color = Soft, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.resetDemo() }, enabled = true) { Text("시연 데이터 초기화", color = Soft) }
    }
}

@Composable
private fun Label(t: String) = Text(t, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Chip(text: String, selected: Boolean, enabled: Boolean = true, highlight: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = when { selected -> Accent; highlight -> Color(0xFF34406A); else -> GlassStrong },
            disabledContainerColor = Color(0xFF141A28),
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(text, color = if (selected) Ink else Color.White, fontSize = 16.sp, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth()) }
}
