package com.iam.bgm

import com.iam.bgm.core.GeoPoint
import com.iam.bgm.core.RoadGraph
import com.iam.bgm.core.Zone
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 자유 주행 길찾기: 도로 위로만, 일방통행 지킴. */
class RoadGraphTest {
    //  0 ── 1
    //  │    ↓ (1→2 일방통행)
    //  3 ── 2
    private val g = RoadGraph(
        nodes = listOf(37.0, 127.0, 37.0, 127.01, 36.99, 127.01, 36.99, 127.0),
        edges = listOf(0, 1, 0, 1, 2, 1, 2, 3, 0, 3, 0, 0),
    )

    @Test fun shortestPathFollowsRoads() {
        // 정사각형 두 변 길이가 같아 두 길 모두 최단 — 대각선(도로 없음)으로는 가지 않는다
        val p = g.shortestPath(0, 2)
        assertTrue("$p", p == listOf(0, 1, 2) || p == listOf(0, 3, 2))
    }

    @Test fun onewayIsRespected() {
        // 2→1은 일방통행을 거스르므로 2→3→0→1로 돌아간다
        assertEquals(listOf(2, 3, 0, 1), g.shortestPath(2, 1))
        val isolated = RoadGraph(listOf(37.0, 127.0, 37.0, 127.01), listOf(0, 1, 1))
        assertNull(isolated.shortestPath(1, 0))
    }

    @Serializable private data class GraphFile(val nodes: List<Double>, val edges: List<Int>)

    /** 실제 앱 데이터: 회사(광화문)에서 집(성수)까지 도로로 이어지고, 길이가 직선거리의 1~2.5배다. */
    @Test fun realGraphConnectsOfficeAndHome() {
        val f = com.iam.bgm.platform.BgmJson.decodeFromString(GraphFile.serializer(), File("src/main/assets/road_graph.json").readText())
        val road = RoadGraph(f.nodes, f.edges)
        val office = TestData.scenario.route.first { it.id == "office" }.point
        val home = TestData.scenario.route.first { it.id == "home" }.point
        val path = road.route(office, home)
        assertNotNull(path)
        val len = path!!.zipWithNext { a, b -> Zone.metersBetween(a, b) }.sum()
        val straight = Zone.metersBetween(office, home)
        assertTrue("길이 ${len.toInt()}m / 직선 ${straight.toInt()}m", len in straight..straight * 2.5)
        // 출발·도착은 도로 위에 붙고, 원래 지점에서 멀지 않다
        assertTrue(Zone.metersBetween(path.first(), office) < 300)
        assertTrue(Zone.metersBetween(path.last(), home) < 300)
    }
}
