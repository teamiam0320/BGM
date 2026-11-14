"""OSM Overpass 결과(out body geom, 노드 id 포함) → 앱 길찾기용 도로 그래프(road_graph.json).

- 교차점(두 도로 이상이 공유하는 노드)과 도로 끝은 반드시 남기고, 그 사이 모양 점은 Douglas-Peucker로 줄인다.
- 일방통행: oneway=yes/true/1 → 정방향만, oneway=-1 → 역방향만, motorway·motorway_link는 일방통행으로 본다.
- 서로 이어지지 않은 작은 조각은 버리고 가장 큰 연결 덩어리만 남긴다(엉뚱한 섬에 붙는 것을 막음).
사용: python -I tools/road_graph_build.py roads.json app/src/main/assets/road_graph.json
"""
import json, math, sys
from collections import defaultdict

SRC, OUT = sys.argv[1], sys.argv[2]
K = math.cos(math.radians(37.55))


def xy(p):
    return (p[1] * 111_320 * K, p[0] * 110_540)


def dp(idx, pts, tol):
    """pts: [(lat,lng)], idx: 같은 길이의 원래 인덱스. 끝점은 항상 남긴다."""
    if len(pts) < 3:
        return list(range(len(pts)))
    a, b = xy(pts[0]), xy(pts[-1])
    bx, by = b[0] - a[0], b[1] - a[1]
    L2 = bx * bx + by * by or 1e-9
    best, bi = -1.0, 0
    for i in range(1, len(pts) - 1):
        c = xy(pts[i]); cx, cy = c[0] - a[0], c[1] - a[1]
        t = max(0.0, min(1.0, (cx * bx + cy * by) / L2))
        d = math.hypot(cx - t * bx, cy - t * by)
        if d > best:
            best, bi = d, i
    if best <= tol:
        return [0, len(pts) - 1]
    left = dp(idx[: bi + 1], pts[: bi + 1], tol)
    right = dp(idx[bi:], pts[bi:], tol)
    return left[:-1] + [r + bi for r in right]


data = json.load(open(SRC, encoding='utf-8'))
ways = [e for e in data['elements'] if e.get('type') == 'way' and 'nodes' in e and 'geometry' in e]
use = defaultdict(int)
for w in ways:
    for n in set(w['nodes']):
        use[n] += 1
    use[w['nodes'][0]] += 1; use[w['nodes'][-1]] += 1  # 끝점은 항상 남긴다

coord = {}
edges = []  # (nodeId a, nodeId b, oneway)
for w in ways:
    t = w.get('tags', {})
    hw = t.get('highway', '')
    ow_tag = t.get('oneway', 'no')
    ow = 1 if ow_tag in ('yes', 'true', '1') or hw in ('motorway', 'motorway_link') else (-1 if ow_tag == '-1' else 0)
    ids = w['nodes']; geo = [(g['lat'], g['lon']) for g in w['geometry']]
    for i, n in enumerate(ids):
        coord[n] = geo[i]
    # 교차점에서 나눈 뒤 각 조각을 단순화
    cut = [0] + [i for i in range(1, len(ids) - 1) if use[ids[i]] >= 2] + [len(ids) - 1]
    for s, e in zip(cut, cut[1:]):
        seg = list(range(s, e + 1))
        keep = dp(seg, [geo[i] for i in seg], 8.0)
        kept = [ids[seg[k]] for k in keep]
        for a, b in zip(kept, kept[1:]):
            if a == b:
                continue
            if ow == -1:
                edges.append((b, a, 1))
            else:
                edges.append((a, b, ow))

# 가장 큰 연결 덩어리(방향 무시)
adj = defaultdict(set)
for a, b, _ in edges:
    adj[a].add(b); adj[b].add(a)
seen, best = set(), []
for start in adj:
    if start in seen:
        continue
    comp, stack = [], [start]
    seen.add(start)
    while stack:
        u = stack.pop(); comp.append(u)
        for v in adj[u]:
            if v not in seen:
                seen.add(v); stack.append(v)
    if len(comp) > len(best):
        best = comp
keep = set(best)
order = sorted(keep)
index = {n: i for i, n in enumerate(order)}
nodes_flat = []
for n in order:
    la, lo = coord[n]
    nodes_flat += [round(la, 5), round(lo, 5)]
edge_flat = []
for a, b, ow in edges:
    if a in keep and b in keep:
        edge_flat += [index[a], index[b], ow]

json.dump({
    'attribution': '© OpenStreetMap contributors',
    'license': 'ODbL 1.0 — https://www.openstreetmap.org/copyright',
    'note': '자유 주행 길찾기용 도로 그래프. nodes=[lat,lng,...], edges=[a,b,oneway,...] (oneway=1이면 a→b만)',
    'nodes': nodes_flat, 'edges': edge_flat,
}, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print('ways', len(ways), 'nodes', len(order), 'edges', len(edge_flat) // 3, 'components dropped', len(adj) - len(keep))
