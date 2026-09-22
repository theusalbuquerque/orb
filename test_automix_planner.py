import unittest

import orb_automix_api as automix


def curve(duration: int, value: float) -> list[dict[str, float]]:
    return [{"time": float(t), "energy": value} for t in range(duration + 1)]


def track(
    *,
    bpm: float = 128.0,
    key: str = "C major",
    vocal: float = 0.10,
    duration: float = 120.0,
) -> dict:
    bar = 4.0 * 60.0 / bpm
    phrase = 4.0 * bar
    downbeats = []
    t = 0.0
    while t <= duration:
        downbeats.append(round(t, 6))
        t += bar
    phrases = []
    t = 0.0
    while t <= duration:
        phrases.append(round(t, 6))
        t += phrase

    energy = curve(int(duration), 0.40)
    return {
        "duration": duration,
        "contentEndTime": duration,
        "audibleStartTime": 0.0,
        "bpm": bpm,
        "beatConfidence": 0.90,
        "key": key,
        "keyConfidence": 0.90,
        "mixInTime": 0.0,
        "mixOutTime": duration,
        "downbeats": downbeats,
        "phraseBoundaries": phrases,
        "mixInCandidates": [
            {"time": 0.0, "score": 0.95, "type": "phrase"},
            {"time": phrase, "score": 0.75, "type": "phrase"},
        ],
        "mixOutCandidates": [],
        "energyCurve": energy,
        "lowEnergyCurve": curve(int(duration), 0.30),
        "vocalActivityMask": [vocal for _ in energy],
        "vocalProbability": vocal,
    }


class AutomixPlannerTest(unittest.TestCase):
    def test_harmonic_relationships_are_musical_not_chromatic(self) -> None:
        c_major = track(key="C major")
        a_minor = track(key="A minor")
        f_major = track(key="F major")
        c_sharp_major = track(key="C# major")

        self.assertGreaterEqual(automix._key_compatibility(c_major, a_minor), 0.90)
        self.assertGreaterEqual(automix._key_compatibility(c_major, f_major), 0.85)
        self.assertLessEqual(automix._key_compatibility(c_major, c_sharp_major), 0.20)

    def test_structural_pair_tracks_release_as_handoff(self) -> None:
        a = track(bpm=128.0)
        b = track(bpm=128.0)
        # 16 beats at 128 BPM = 7.5 s, so the desired overlap starts at 112.5 s.
        pair = automix._best_structural_pair(
            a,
            b,
            116.25,
            120.0,
            0.0,
            120.0,
            0.0,
            16,
            128.0,
            128.0,
            1.0,
        )

        self.assertIsNotNone(pair)
        assert pair is not None
        self.assertAlmostEqual(float(pair["start"]), 112.5, delta=0.6)
        self.assertAlmostEqual(float(pair["releaseFraction"]), 0.5, delta=0.10)
        self.assertGreaterEqual(float(pair["phraseAlignment"]), 0.78)

    def test_compatible_clean_pair_can_choose_real_dj_mix(self) -> None:
        a = track(bpm=128.0, key="C major", vocal=0.10)
        b = track(bpm=130.0, key="A minor", vocal=0.10)

        best, candidates = automix._remote_plan(a, b)

        self.assertIn(best["style"], {"DJ_BLEND", "EQ_SWAP"})
        self.assertNotEqual(best["style"], "NO_TRANSITION")
        self.assertTrue(any(c["style"] in {"DJ_BLEND", "EQ_SWAP"} for c in candidates))

    def test_harmonic_conflict_does_not_become_filtered_crossfade(self) -> None:
        a = track(bpm=128.0, key="C major", vocal=0.10)
        b = track(bpm=129.0, key="C# major", vocal=0.10)

        best, candidates = automix._remote_plan(a, b)
        overlap_styles = {"DJ_BLEND", "DJ_FILTER", "EQ_SWAP"}

        self.assertNotIn(best["style"], overlap_styles)
        self.assertFalse(any(c["style"] in overlap_styles for c in candidates))

    def test_heavy_vocal_collision_rejects_long_overlap(self) -> None:
        a = track(bpm=128.0, key="C major", vocal=0.95)
        b = track(bpm=129.0, key="A minor", vocal=0.95)

        best, candidates = automix._remote_plan(a, b)
        overlap_styles = {"DJ_BLEND", "DJ_FILTER", "EQ_SWAP"}

        self.assertNotIn(best["style"], overlap_styles)
        self.assertFalse(any(c["style"] in overlap_styles for c in candidates))


if __name__ == "__main__":
    unittest.main()
