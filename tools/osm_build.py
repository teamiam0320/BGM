"""OSM Overpass 결과 → 앱 지도 데이터(osm_map.json). 도로·물길은 단순화(Douglas-Peucker), 범위 밖은 잘라낸다."""
import json, math, sys

SRC, OUT = sys.argv[1], sys.argv[2]
S, W, N, E = 37.505, 126.925, 37.585, 127.075
LAT0 = (S + N) / 2
KX = 111_320 * math.cos(math.radians(LAT0))
KY = 110_540


def xy(p):
    return ((p[1] - W) * KX, (p[0] - S) * KY)


def dp(pts, tol):
    if len(pts) < 3:
        return pts
    a, b = xy(pts[0]), xy(pts[-1])
    dx, dy = b[0] - a[0], b[1] - a[1]
    L = math.hypot(dx, dy) or 1e-9
    best, idx = -1, 0
    for i in range(1, len(pts) - 1):
        c = xy(pts[i])
        d = abs(dy * c[0] - dx * c[1] + b[0] * a[1] - b[1] * a[0]) / L
        if d > best:
            best, idx = d, i
    if best <= tol:
        return [pts[0], pts[-1]]
    return dp(pts[: idx + 1], tol)[:-1] + dp(pts[idx:], tol)


def inside(p, m=0.004):
    return S - m <= p[0] <= N + m and W - m <= p[1] <= E + m


def clip_runs(pts):
    """범위 안(여유 포함)에 걸친 연속 구간만 남긴다. 경계 밖 한 점씩은 이어서 선이 끊기지 않게."""
    runs, cur = [], []
    for i, p in enumerate(pts):
        keep = inside(p) or (i > 0 and inside(pts[i - 1])) or (i + 1 < len(pts) and inside(pts[i + 1]))
        if keep:
            cur.append(p)
        elif cur:
            runs.append(cur); cur = []
    if cur:
        runs.append(cur)
    return [r for r in runs if len(r) >= 2]


def flat(pts):
    out = []
    for la, lo in pts:
        out += [round(la, 5), round(lo, 5)]
    return out


def area_m2(pts):
    q = [xy(p) for p in pts]
    return abs(sum(q[i][0] * q[i - 1][1] - q[i - 1][0] * q[i][1] for i in range(len(q)))) / 2


data = json.load(open(SRC, encoding='utf-8'))
roads, water, parks = [], [], []
stats = {'roads_in': 0, 'water_in': 0, 'parks_in': 0}
for el in data.get('elements', []):
    if el.get('type') != 'way' or 'geometry' not in el:
        continue
    t = el.get('tags', {})
    pts = [(g['lat'], g['lon']) for g in el['geometry']]
    hw, ww = t.get('highway'), t.get('waterway')
    if hw in ('motorway', 'trunk', 'primary'):
        stats['roads_in'] += 1
        cls = {'motorway': 'm', 'trunk': 't', 'primary': 'p'}[hw]
        for run in clip_runs(pts):
            s = dp(run, 12.0)
            if len(s) >= 2:
                roads.append({'c': cls, 'pts': flat(s)})
    elif ww in ('river', 'stream', 'canal'):
        stats['water_in'] += 1
        name = t.get('name', '')
        width = 900 if name == '한강' else (110 if ww == 'river' else 30)
        for run in clip_runs(pts):
            s = dp(run, 10.0)
            if len(s) >= 2:
                water.append({'w': width, 'name': name, 'pts': flat(s)})
    elif t.get('leisure') == 'park' and len(pts) >= 4 and pts[0] == pts[-1]:
        stats['parks_in'] += 1
        if area_m2(pts) >= 150_000 and any(inside(p, 0) for p in pts):
            parks.append({'name': t.get('name', ''), 'pts': flat(dp(pts, 15.0))})

out = {
    'attribution': '© OpenStreetMap contributors',
    'license': 'ODbL 1.0 — https://www.openstreetmap.org/copyright',
    'bbox': [S, W, N, E],
    'roads': roads, 'water': water, 'parks': parks,
}
json.dump(out, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print(stats)
print('roads', len(roads), 'water', len(water), 'parks', len(parks), [p['name'] for p in parks])
print('water names', sorted({w['name'] for w in water}))
