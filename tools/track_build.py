"""OSRM 경로 → demo_scenario.json의 자동 주행 데이터(track·autoDrive). 시나리오 지점은 경로 위로 옮긴다."""
import json, math, sys

ROUTE, SCEN = sys.argv[1], sys.argv[2]
r = json.load(open(ROUTE, encoding='utf-8'))
d = json.load(open(SCEN, encoding='utf-8'))
pts = [(c[1], c[0]) for c in r['routes'][0]['geometry']['coordinates']]
K = math.cos(math.radians(37.55))


def dist(a, b):
    return math.hypot((a[0] - b[0]) * 110540, (a[1] - b[1]) * 111320 * K)


def dp(p, tol):
    if len(p) < 3:
        return p
    a, b = p[0], p[-1]
    best, idx = -1, 0
    for i in range(1, len(p) - 1):
        # 점-선분 거리(미터)
        ax, ay = 0, 0
        bx, by = (b[1] - a[1]) * 111320 * K, (b[0] - a[0]) * 110540
        cx, cy = (p[i][1] - a[1]) * 111320 * K, (p[i][0] - a[0]) * 110540
        L2 = bx * bx + by * by or 1e-9
        t = max(0, min(1, (cx * bx + cy * by) / L2))
        dd = math.hypot(cx - t * bx, cy - t * by)
        if dd > best:
            best, idx = dd, i
    if best <= tol:
        return [a, b]
    return dp(p[: idx + 1], tol)[:-1] + dp(p[idx:], tol)


track = dp(pts, 6.0)
cum = [0.0]
for a, b in zip(track, track[1:]):
    cum.append(cum[-1] + dist(a, b))
total = cum[-1]


def nearest(target):
    i = min(range(len(track)), key=lambda k: dist(track[k], target))
    return i, track[i], cum[i] / total


# 시나리오 지점을 경로 위로 (사무실·집은 경로 시작·끝)
route = {x['id']: x for x in d['route']}
moved = {}
for rid, target in [('downtown', (37.5547, 126.9707)), ('riverside', (37.5280, 127.0150)), ('seongsu', (37.5395, 127.0450))]:
    i, p, f = nearest(target)
    route[rid]['lat'], route[rid]['lng'] = round(p[0], 5), round(p[1], 5)
    moved[rid] = (p, f)
route['office']['lat'], route['office']['lng'] = round(track[0][0], 5), round(track[0][1], 5)
route['home']['lat'], route['home']['lng'] = round(track[-1][0], 5), round(track[-1][1], 5)

# 경로 위로 옮긴 지점의 씨앗 편지도 같은 구간으로
for s in d['seedLetters']:
    if s['id'].startswith('seed_downtown'):
        s['zoneLat'], s['zoneLng'] = route['downtown']['lat'], route['downtown']['lng']
    if s['id'] == 'seed_seongsu_1':
        s['zoneLat'], s['zoneLng'] = route['seongsu']['lat'], route['seongsu']['lng']

rv = round(moved['riverside'][1], 3)
flat = []
for la, lo in track:
    flat += [round(la, 5), round(lo, 5)]
d['track'] = flat
d['autoDrive'] = {
    'note': '자동 주행 시연. at = 경로 진행률(0~1). 발화는 에이미가 말을 마친 뒤에 나온다. whenOffer = 노래 제안을 받은 뒤에만.',
    'drivers': {
        'driver_a': {'minutes': 34, 'events': [
            {'at': 0.0, 'gear': 'D'},
            {'at': 0.03, 'say': 0}, {'at': 0.14, 'say': 1}, {'at': 0.30, 'say': 2},
            {'at': rv, 'say': 3}, {'at': rv, 'say': 4},
            {'at': 1.0, 'gear': 'P'},
        ]},
        'driver_b': {'minutes': 26, 'events': [
            {'at': 0.0, 'gear': 'D'},
            {'at': 0.03, 'say': 0}, {'at': 0.12, 'say': 1},
            {'at': rv, 'say': 2, 'whenOffer': True}, {'at': rv, 'say': 3},
            {'at': 1.0, 'gear': 'P'},
        ]},
    },
}
json.dump(d, open(SCEN, 'w', encoding='utf-8', newline='\n'), ensure_ascii=False, indent=2)
print('track pts', len(track), 'km', round(total / 1000, 2), 'riverside at', rv)
print({k: (round(v[0][0], 5), round(v[0][1], 5), round(v[1], 3)) for k, v in moved.items()})
