"""BGM 데모 음원 생성기 — 저작권 문제 없는 자체 합성 앰비언트 트랙.

같은 시드면 같은 파일이 나온다(재현성). 실행: python -I tools/generate_tracks.py <out_dir>
"""
import sys, wave
import numpy as np

SR = 22050
DUR = 40.0

def note(n):  # MIDI -> Hz
    return 440.0 * 2 ** ((n - 69) / 12)

def pad(freqs, t, detune=0.003):
    out = np.zeros_like(t)
    for f in freqs:
        for d in (-detune, 0, detune):
            out += np.sin(2 * np.pi * f * (1 + d) * t) * 0.5 + np.sin(4 * np.pi * f * (1 + d) * t) * 0.12
    return out / (len(freqs) * 3)

def env_chords(chords, t, seg):
    out = np.zeros_like(t)
    for i, ch in enumerate(chords):
        a, b = i * seg, (i + 1) * seg
        m = (t >= a) & (t < b)
        local = t[m] - a
        e = np.minimum(1, local / 2.0) * np.minimum(1, (seg - local) / 2.0)
        out[m] += pad([note(n) for n in ch], t[m]) * e
    return out

def arp(chords, t, seg, step, vol, seed_shift=0):
    out = np.zeros_like(t)
    n_steps = int(DUR / step)
    for k in range(n_steps):
        st = k * step
        ch = chords[int(st // seg) % len(chords)]
        n = ch[(k + seed_shift) % len(ch)] + 12
        m = (t >= st) & (t < st + step * 1.8)
        local = t[m] - st
        out[m] += np.sin(2 * np.pi * note(n) * local) * np.exp(-local * 3.5) * vol
    return out

TRACKS = {
    # id: (chords as MIDI, arp step sec or None, noise level, seed)
    "calm_drive":     ([[48, 55, 64, 71], [53, 60, 64, 69], [45, 52, 60, 67], [50, 57, 65, 72]], 0.75, 0.0, 1),
    "comfort_rain":   ([[53, 57, 60, 64], [45, 52, 57, 60], [50, 57, 60, 65], [48, 55, 60, 64]], None, 0.05, 2),
    "warm_home":      ([[48, 52, 55, 59], [53, 57, 60, 64], [55, 59, 62, 67], [48, 52, 55, 64]], 1.0, 0.0, 3),
    "bright_morning": ([[50, 57, 62, 66], [55, 62, 66, 71], [47, 54, 62, 66], [52, 59, 64, 67]], 0.25, 0.0, 4),
    "night_city":     ([[45, 52, 55, 60], [41, 48, 55, 57], [43, 50, 55, 59], [40, 47, 55, 59]], 0.5, 0.012, 5),
    "dream_road":     ([[52, 59, 63, 68], [49, 56, 61, 64], [45, 52, 57, 64], [47, 54, 59, 63]], 0.375, 0.0, 6),
}

def render(chords, step, noise, seed):
    rng = np.random.default_rng(seed)
    t = np.arange(int(SR * DUR)) / SR
    seg = DUR / (len(chords) * 2)
    full = chords * 2
    y = env_chords(full, t, seg) * 0.6
    if step:
        y += arp(full, t, seg, step, 0.18)
    if noise:
        n = rng.standard_normal(len(t))
        n = np.convolve(n, np.ones(40) / 40, mode="same")  # 부드러운 빗소리 질감
        y += n * noise * 8
    fade = np.minimum(1, np.minimum(t / 1.5, (DUR - t) / 1.5))
    y = y * fade
    y = y / (np.max(np.abs(y)) + 1e-9) * 0.7
    return (y * 32767).astype(np.int16)

def main(out_dir):
    for tid, (chords, step, noise, seed) in TRACKS.items():
        pcm = render(chords, step, noise, seed)
        with wave.open(f"{out_dir}/{tid}.wav", "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        print(tid, len(pcm) / SR, "s")

if __name__ == "__main__":
    main(sys.argv[1])
