import unittest

import numpy as np

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
    def test_client_model_evidence_overrides_cached_transition_windows(self) -> None:
        cached = track(bpm=128.0, key="C major", vocal=0.10, duration=10.0)
        cached["downbeats"] = [0.0, 2.0, 4.0, 6.0, 8.0]
        cached["beatConfidence"] = 0.45
        cached["vocalActivityMask"] = [0.10 for _ in cached["energyCurve"]]

        client = dict(cached)
        client["beatConfidence"] = 0.90
        client["downbeats"] = [1.0, 3.0, 7.0, 9.0]
        client["vocalActivityMask"] = [
            0.90,
            0.85,
            0.50,
            0.50,
            0.50,
            0.50,
            0.50,
            0.50,
            0.75,
            0.80,
            0.82,
        ]

        merged = automix._merge_plan_evidence(cached, client)

        self.assertEqual(merged["downbeats"], [1.0, 3.0, 7.0, 9.0])
        self.assertEqual(merged["beatEvidence"], "client-model")
        self.assertEqual(merged["vocalEvidence"], "client-model-window")
        # Model evidence replaces head/tail.
        self.assertAlmostEqual(merged["vocalActivityMask"][0], 0.90)
        self.assertAlmostEqual(merged["vocalActivityMask"][-1], 0.82)
        # Neutral model placeholders do not erase the server's full-track evidence.
        self.assertAlmostEqual(merged["vocalActivityMask"][5], 0.10)

    def test_provisional_client_evidence_never_erases_cached_structure(self) -> None:
        cached = track(bpm=128.0, key="C major", vocal=0.20, duration=10.0)
        original_downbeats = list(cached["downbeats"])
        original_mask = list(cached["vocalActivityMask"])
        client = {
            "trackId": "same",
            "duration": 10.0,
            "bpm": 128.0,
            "beatConfidence": 0.30,
            "downbeats": [],
            "energyCurve": [],
            "vocalActivityMask": [],
            "phraseBoundaries": [],
            "mixInCandidates": [],
            "mixOutCandidates": [],
        }

        merged = automix._merge_plan_evidence(cached, client)

        self.assertEqual(merged["downbeats"], original_downbeats)
        self.assertEqual(merged["vocalActivityMask"], original_mask)
        self.assertTrue(merged["phraseBoundaries"])
        self.assertTrue(merged["energyCurve"])


    def test_downbeat_phase_follows_recurring_accents(self) -> None:
        hop = 0.10
        beats = [i * 0.50 for i in range(24)]
        onset = np.zeros(140, dtype=np.float64)

        # Beat index 2 of each four-beat group carries the recurring bar accent.
        for index, beat in enumerate(beats):
            frame = int(round(beat / hop))
            onset[frame] = 1.0 if index % 4 == 2 else 0.20

        downbeats = automix._downbeats_from_beats(beats, onset, hop)

        self.assertGreaterEqual(len(downbeats), 4)
        self.assertAlmostEqual(downbeats[0], 1.0, delta=0.11)
        self.assertAlmostEqual(downbeats[1] - downbeats[0], 2.0, delta=0.11)

    def test_phrase_phase_follows_arrangement_changes(self) -> None:
        downbeats = [i * 2.0 for i in range(16)]
        times = np.arange(0.0, 32.0, 0.5, dtype=np.float64)

        # Four-bar sections change at downbeat indices 1, 5, 9 and 13.
        energy = np.zeros(times.size, dtype=np.float64)
        low = np.zeros(times.size, dtype=np.float64)
        vocal = np.zeros(times.size, dtype=np.float64)
        for i, t in enumerate(times):
            section = int(max(0.0, t - 2.0) // 8.0)
            high = section % 2 == 1
            energy[i] = 0.80 if high else 0.20
            low[i] = 0.72 if high else 0.18
            vocal[i] = 0.62 if high else 0.15

        phrases = automix._phrase_boundaries_from_structure(
            downbeats,
            times,
            energy,
            low,
            vocal,
        )

        self.assertGreaterEqual(len(phrases), 3)
        self.assertAlmostEqual(phrases[0], 2.0, delta=0.1)
        self.assertAlmostEqual(phrases[1] - phrases[0], 8.0, delta=0.1)


    def test_long_track_key_analysis_preserves_pitch(self) -> None:
        duration_seconds = 96
        samples = int(automix.SAMPLE_RATE * duration_seconds)
        time = np.arange(samples, dtype=np.float32) / float(automix.SAMPLE_RATE)
        audio = np.zeros(samples, dtype=np.float32)

        # C major across several octaves. The previous long-track sampler treated
        # distant samples as adjacent audio and could shift this chroma upward.
        for frequency in (130.8128, 261.6256, 329.6276, 391.9954, 523.2511):
            audio += np.sin(2.0 * np.pi * frequency * time).astype(np.float32)
        audio /= 5.0

        key, confidence = automix._key_from_audio(audio)

        self.assertEqual(key, "C major")
        self.assertGreaterEqual(confidence, 0.25)


    def test_transition_uses_tail_and_head_tempo_before_global_bpm(self) -> None:
        a = track(bpm=100.0)
        b = track(bpm=150.0)
        a["tailBpm"] = 128.0
        a["tailBeatConfidence"] = 0.80
        b["headBpm"] = 130.0
        b["headBeatConfidence"] = 0.85

        outgoing, incoming, compatibility = automix._tempo_pair(a, b)

        self.assertAlmostEqual(outgoing, 128.0, delta=0.01)
        self.assertAlmostEqual(incoming, 130.0, delta=0.01)
        self.assertGreater(compatibility, 0.85)

    def test_low_confidence_local_tempo_falls_back_to_global_bpm(self) -> None:
        a = track(bpm=128.0)
        b = track(bpm=130.0)
        a["tailBpm"] = 90.0
        a["tailBeatConfidence"] = 0.10
        b["headBpm"] = 180.0
        b["headBeatConfidence"] = 0.10

        outgoing, incoming, compatibility = automix._tempo_pair(a, b)

        self.assertAlmostEqual(outgoing, 128.0, delta=0.01)
        self.assertAlmostEqual(incoming, 130.0, delta=0.01)
        self.assertGreater(compatibility, 0.85)


    def test_transition_uses_tail_and_head_keys_before_global_keys(self) -> None:
        a = track(key="C major")
        b = track(key="C# major")
        a["tailKey"] = "G major"
        a["tailKeyConfidence"] = 0.90
        b["headKey"] = "G major"
        b["headKeyConfidence"] = 0.90

        self.assertEqual(automix._key_compatibility(a, b), 1.0)

    def test_low_confidence_local_key_falls_back_to_global_key(self) -> None:
        a = track(key="C major")
        b = track(key="A minor")
        a["tailKey"] = "C# major"
        a["tailKeyConfidence"] = 0.10
        b["headKey"] = "F# major"
        b["headKeyConfidence"] = 0.10

        self.assertGreaterEqual(automix._key_compatibility(a, b), 0.90)


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

    def test_unknown_key_cannot_authorize_long_overlap(self) -> None:
        a = track(bpm=128.0, key="C major", vocal=0.10)
        b = track(bpm=129.0, key="", vocal=0.10)
        b["keyConfidence"] = 0.0

        best, candidates = automix._remote_plan(a, b)
        overlap_styles = {"DJ_BLEND", "DJ_FILTER", "EQ_SWAP"}

        self.assertNotIn(best["style"], overlap_styles)
        self.assertFalse(any(c["style"] in overlap_styles for c in candidates))

    def test_protected_outgoing_track_hands_off_late(self) -> None:
        a = track(bpm=128.0, key="C major", vocal=0.10)
        b = track(bpm=129.0, key="A minor", vocal=0.10)

        # Keep A dense and vocal through the final phrase so _release_landmarks()
        # protects it almost to contentEnd instead of authorizing an early takeover.
        for point in a["energyCurve"]:
            if point["time"] >= 108.0:
                point["energy"] = 0.82
        for index, point in enumerate(a["energyCurve"]):
            if point["time"] >= 108.0:
                a["vocalActivityMask"][index] = 0.72
        a["vocalProbability"] = 0.72

        best, _ = automix._remote_plan(a, b)

        if best["style"] in {"DJ_BLEND", "DJ_FILTER", "EQ_SWAP"}:
            self.assertGreaterEqual(float(best["handoffFraction"]), 0.84)
        else:
            self.assertIn(best["style"], {"PHRASE_CUT", "CUT", "NO_TRANSITION"})


    def test_heavy_vocal_collision_rejects_long_overlap(self) -> None:
        a = track(bpm=128.0, key="C major", vocal=0.95)
        b = track(bpm=129.0, key="A minor", vocal=0.95)

        best, candidates = automix._remote_plan(a, b)
        overlap_styles = {"DJ_BLEND", "DJ_FILTER", "EQ_SWAP"}

        self.assertNotIn(best["style"], overlap_styles)
        self.assertFalse(any(c["style"] in overlap_styles for c in candidates))


if __name__ == "__main__":
    unittest.main()
