package com.iam.bgm.core

import java.util.PriorityQueue

/**
 * 오프라인 길찾기용 도로 그래프(OpenStreetMap 간선·보조간선·연결로, tools/road_graph_build.py).
 * nodes = [lat, lng, ...], edges = [a, b, oneway, ...] (oneway=1이면 a→b만, 0이면 양방향).
 * 네트워크 없이 같은 입력이면 같은 경로를 낸다(다익스트라, 동점은 노드 번호 순).
 */
class RoadGraph(nodes: List<Double>, edges: List<Int>) {
    val points: List<GeoPoint> = nodes.chunked(2).map { GeoPoint(it[0], it[1]) }
    private val adj: Array<MutableList<Pair<Int, Double>>> = Array(points.size) { mutableListOf() }

    init {
        var i = 0
        while (i + 2 < edges.size) {
            val a = edges[i]; val b = edges[i + 1]; val oneway = edges[i + 2]
            val w = Zone.metersBetween(points[a], points[b])
            adj[a] += b to w
            if (oneway == 0) adj[b] += a to w
            i += 3
        }
    }

    /** 가까운 노드 [k]개(가까운 순). */
    fun nearest(p: GeoPoint, k: Int = 1): List<Int> =
        points.indices.sortedWith(compareBy<Int> { Zone.metersBetween(p, points[it]) }.thenBy { it }).take(k)

    /**
     * [from]에서 [to]까지 도로를 따라가는 경로(도로 위 점 목록). 출발·도착은 가까운 도로 노드에 붙인다.
     * 일방통행 때문에 가장 가까운 노드에서 길이 막히면 그다음 가까운 노드들로 다시 시도한다. 길이 없으면 null.
     */
    fun route(from: GeoPoint, to: GeoPoint, candidates: Int = 6): List<GeoPoint>? {
        val starts = nearest(from, candidates)
        val ends = nearest(to, candidates)
        for (s in starts) for (t in ends) {
            shortestPath(s, t)?.let { return it.map { points[it] } }
        }
        return null
    }

    fun shortestPath(s: Int, t: Int): List<Int>? {
        if (s == t) return listOf(s)
        val dist = DoubleArray(points.size) { Double.MAX_VALUE }
        val prev = IntArray(points.size) { -1 }
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy<Pair<Double, Int>> { it.first }.thenBy { it.second })
        dist[s] = 0.0; pq += 0.0 to s
        while (pq.isNotEmpty()) {
            val (d, u) = pq.remove()
            if (d > dist[u]) continue
            if (u == t) break
            for ((v, w) in adj[u]) {
                val nd = d + w
                if (nd < dist[v]) { dist[v] = nd; prev[v] = u; pq += nd to v }
            }
        }
        if (dist[t] == Double.MAX_VALUE) return null
        val path = ArrayList<Int>()
        var c = t
        while (c != -1) { path += c; c = prev[c] }
        return path.reversed()
    }
}
