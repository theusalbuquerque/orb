import asyncio
import unittest

import numpy as np
from fastapi import HTTPException

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


def add_curve_analysis(
    item: dict,
    *,
    pitch_class: int = 0,
    onset_shift: int = 0,
    local_bpm: float | None = None,
) -> dict:
    duration = int(item["duration"])
    beats = []
    beat = 60.0 / float(item["bpm"])
    t = 0.0
    while t <= duration:
        beats.append(round(t, 6))
        t += beat
    item["beats"] = beats
    item["analysisSchema"] = automix.ANALYSIS_SCHEMA

    mid = []
    high = []
    bright = []
    onset = []
    chroma = []
    for second in range(duration + 1):
        phase = (second + onset_shift) % 8
        mid.append({"time": float(second), "energy": 0.45 + 0.18 * (phase / 7.0)})
        high.append({"time": float(second), "energy": 0.22 + 0.12 * ((7 - phase) / 7.0)})
        bright.append({"time": float(second), "energy": 0.38 + 0.08 * (phase / 7.0)})
        onset.append({"time": float(second), "energy": 1.0 if phase in {0, 4} else 0.08})
        vector = [0.01] * 12
        vector[pitch_class % 12] = 0.70
        vector[(pitch_class + 4) % 12] = 0.16
        vector[(pitch_class + 7) % 12] = 0.08
        total = sum(vector)
        chroma.append({
            "time": float(second),
            "chroma": [value / total for value in vector],
        })
    item["midEnergyCurve"] = mid
    item["highEnergyCurve"] = high
    item["brightnessCurve"] = bright
    item["onsetCurve"] = onset
    item["chromaCurve"] = chroma

    bpm_value = float(local_bpm if local_bpm is not None else item["bpm"])
    item["tempoCurve"] = [
        {"time": float(t), "bpm": bpm_value, "confidence": 0.90}
        for t in range(0, duration + 1, 6)
    ]
    return item


class AutomixPlannerTest(unittest.TestCase):
    def test_automix_25_server_rejects_missing_entitlement(self) -> None:
        request = automix.PlanRequest(
            version=automix.API_VERSION,
            preview=True,
            accountHash="not-entitled",
            outgoing=track(),
            incoming=track(),
        )

        with self.assertRaises(HTTPException) as raised:
            asyncio.run(automix.plan(request))

        self.assertEqual(raised.exception.status_code, 403)

    def test_automix_25_server_accepts_owner_beta_entitlement(self) -> None:
        request = automix.PlanRequest(
            version=automix.API_VERSION,
            preview=True,
            accountHash=automix._AUTOMIX25_OWNER_HASH,
            outgoing=track(),
            incoming=track(key="A minor"),
        )

        result = asyncio.run(automix.plan(request))

        self.assertEqual(result["automixVersion"], "2.5")
        self.assertEqual(result["authority"], "server")
        self.assertIn(result["style"], {
            "RUNWAY_BLEND", "INTRO_BED", "INTRO_BRIDGE_FILTER",
            "FOREGROUND_TAKEOVER", "PHRASE_TAKEOVER",
            "DJ_BLEND", "DJ_FILTER", "EQ_SWAP", "PHRASE_CUT", "CUT",
        })

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


    def test_quiet_file_tail_prefers_takeover_over_no_transition(self) -> None:
        a = add_curve_analysis(track(bpm=120.0, key="C major", vocal=0.08, duration=120.0))
        b = add_curve_analysis(track(bpm=123.0, key="A minor", vocal=0.08, duration=120.0))

        # A's meaningful content resolves six seconds before the file itself ends.
        # This models the real recording where Orb had already detected the end
        # of musical content but still let several seconds of near-silence play.
        a["contentEndTime"] = 114.0
        a["outroStartTime"] = 108.0
        a["mixOutTime"] = 113.0
        for point in a["energyCurve"]:
            if point["time"] >= 114.0:
                point["energy"] = 0.02
        a["vocalActivityMask"] = [
            0.02 if point["time"] >= 114.0 else 0.08
            for point in a["energyCurve"]
        ]

        best, candidates = automix._remote_plan(a, b)

        self.assertNotEqual(best["style"], "NO_TRANSITION")
        self.assertFalse(any(candidate["style"] == "NO_TRANSITION" for candidate in candidates))
        self.assertTrue(any(
            candidate["reason"] == "server-quiet-tail-takeover"
            for candidate in candidates
        ))

    def test_normal_planner_never_emits_no_transition(self) -> None:
        pairs = [
            (track(bpm=128.0, key="C major", vocal=0.95), track(bpm=97.0, key="F# major", vocal=0.95)),
            (track(bpm=80.0, key="C major", vocal=0.10), track(bpm=170.0, key="F# minor", vocal=0.10)),
            (track(bpm=128.0, key="", vocal=0.70), track(bpm=128.0, key="", vocal=0.70)),
        ]

        for outgoing, incoming in pairs:
            best, candidates = automix._remote_plan(outgoing, incoming)
            self.assertNotEqual(best["style"], "NO_TRANSITION")
            self.assertFalse(any(
                candidate["style"] == "NO_TRANSITION"
                for candidate in candidates
            ))

    def test_selection_score_is_style_name_agnostic(self) -> None:
        base = {
            "score": 0.72,
            "pairCompatibility": 0.74,
            "curveCompatibility": 0.69,
            "phraseAlignment": 0.77,
            "tempoCompatibility": 0.71,
            "keyCompatibility": 0.64,
            "overlapVocalClash": 0.18,
            "energyCompatibility": 0.73,
            "spanCompatibility": 0.80,
        }
        scores = {
            style: automix._candidate_selection_score({"style": style, **base})
            for style in (
                "RUNWAY_BLEND", "PHRASE_TAKEOVER", "DJ_BLEND",
                "DJ_FILTER", "EQ_SWAP", "PHRASE_CUT", "CUT",
            )
        }
        self.assertEqual(len(set(scores.values())), 1)

    def test_curve_alignment_fine_tunes_incoming_beat_phase(self) -> None:
        a = track(bpm=120.0, key="C major", vocal=0.10)
        b = track(bpm=120.0, key="A minor", vocal=0.10)
        a["beats"] = [i * 0.5 for i in range(241)]
        b["beats"] = [i * 0.5 for i in range(241)]

        # Structural detection is intentionally 200 ms late. The fine pass must
        # stay inside the same local musical neighbourhood and move B back onto
        # the actual beat rather than accepting that audible flam.
        refined = automix._refine_curve_aligned_cue(
            a,
            b,
            10.0,
            18.0,
            0.20,
            0.0,
            120.0,
            120.0,
            120.0,
            1.0,
            1.0,
        )

        self.assertAlmostEqual(float(refined["cue"]), 0.0, delta=0.04)
        self.assertLess(float(refined["shiftMs"]), -150.0)
        self.assertGreater(float(refined["beatPhaseFit"]), 0.95)
        self.assertLess(abs(float(refined["shiftMs"])), 410.0)

    def test_curve_lock_can_apply_one_semitone_when_chroma_materially_improves(self) -> None:
        a = add_curve_analysis(track(bpm=124.0, key="C major", vocal=0.10), pitch_class=0)
        b = add_curve_analysis(track(bpm=124.0, key="C# major", vocal=0.10), pitch_class=1)

        shift, locked, unshifted = automix._harmonic_lock_shift(
            a,
            b,
            104.0,
            112.0,
            0.0,
            1.0,
            1.0,
            0.10,
        )

        self.assertEqual(shift, -1.0)
        self.assertGreater(locked, 0.90)
        self.assertGreater(locked, unshifted + 0.20)

    def test_curve_lock_tempo_envelope_keeps_effective_bpms_together(self) -> None:
        a = add_curve_analysis(track(bpm=124.0, key="C major"), pitch_class=0, local_bpm=124.0)
        b = add_curve_analysis(track(bpm=128.0, key="A minor"), pitch_class=9, local_bpm=128.0)
        a["tempoCurve"] = [
            {"time": 104.0, "bpm": 124.0, "confidence": 0.90},
            {"time": 108.0, "bpm": 125.0, "confidence": 0.90},
            {"time": 112.0, "bpm": 126.0, "confidence": 0.90},
        ]
        b["tempoCurve"] = [
            {"time": 0.0, "bpm": 128.0, "confidence": 0.90},
            {"time": 4.0, "bpm": 127.0, "confidence": 0.90},
            {"time": 8.0, "bpm": 126.0, "confidence": 0.90},
        ]

        envelope = automix._tempo_envelope_for_overlap(
            a,
            b,
            104.0,
            112.0,
            0.0,
            1.0,
            1.0,
        )

        self.assertGreaterEqual(len(envelope), 5)
        for point in envelope:
            effective_a = float(point["outgoingBpm"]) * float(point["outgoingRate"])
            effective_b = float(point["incomingBpm"]) * float(point["incomingRate"])
            self.assertAlmostEqual(effective_a, effective_b, delta=0.15)

    def test_curve_metrics_reward_harmonic_and_transient_alignment(self) -> None:
        a = add_curve_analysis(track(bpm=128.0, key="C major"), pitch_class=0)
        b = add_curve_analysis(track(bpm=128.0, key="C major"), pitch_class=0)

        aligned = automix._transition_curve_metrics(a, b, 104.0, 112.0, 0.0, 1.0, 1.0)

        shifted = add_curve_analysis(track(bpm=128.0, key="C major"), pitch_class=6, onset_shift=2)
        misaligned = automix._transition_curve_metrics(a, shifted, 104.0, 112.0, 0.0, 1.0, 1.0)

        self.assertTrue(aligned["evidence"])
        self.assertGreater(float(aligned["harmonicFit"]), 0.90)
        self.assertGreater(float(aligned["onsetFit"]), 0.80)
        self.assertGreater(float(aligned["compatibility"]), float(misaligned["compatibility"]) + 0.15)

    def test_structural_pair_uses_local_tempo_curve_at_join(self) -> None:
        a = add_curve_analysis(track(bpm=120.0, key="C major"), pitch_class=0, local_bpm=128.0)
        b = add_curve_analysis(track(bpm=140.0, key="C major"), pitch_class=0, local_bpm=130.0)

        pair = automix._best_structural_pair(
            a,
            b,
            116.0,
            120.0,
            0.0,
            120.0,
            0.0,
            16,
            120.0,
            140.0,
            1.0,
        )

        self.assertIsNotNone(pair)
        assert pair is not None
        self.assertAlmostEqual(float(pair["outgoingBpm"]), 128.0, delta=0.5)
        self.assertAlmostEqual(float(pair["incomingBpm"]), 130.0, delta=0.5)
        self.assertGreater(float(pair["localTempoFit"]), 0.85)
        self.assertGreater(float(pair["beatPhaseFit"]), 0.80)

    def test_spectral_feature_bundle_exposes_timbre_and_chroma(self) -> None:
        sample_count = int(automix.SAMPLE_RATE * 2.0)
        time = np.arange(sample_count, dtype=np.float32) / float(automix.SAMPLE_RATE)
        audio = (
            0.55 * np.sin(2.0 * np.pi * 130.8128 * time)
            + 0.30 * np.sin(2.0 * np.pi * 261.6256 * time)
            + 0.15 * np.sin(2.0 * np.pi * 1046.502 * time)
        ).astype(np.float32)
        frame = max(1024, int(0.50 * automix.SAMPLE_RATE))
        frames = automix._frame_signal(audio, frame, frame)

        low, mid, high, brightness, chroma = automix._spectral_transition_features(frames)

        self.assertEqual(low.shape[0], frames.shape[0])
        self.assertEqual(mid.shape, low.shape)
        self.assertEqual(high.shape, low.shape)
        self.assertEqual(brightness.shape, low.shape)
        self.assertEqual(chroma.shape, (frames.shape[0], 12))
        self.assertTrue(np.all(brightness >= 0.0))
        self.assertTrue(np.all(brightness <= 1.0))
        self.assertGreater(float(np.mean(chroma[:, 0])), 0.20)

    def test_legacy_server_beat_grid_has_no_librosa_dependency(self) -> None:
        hop = 0.10
        onset = np.zeros(100, dtype=np.float64)
        for frame in range(0, onset.size, 5):
            onset[frame] = 1.0

        original_native = automix._beat_grid
        try:
            automix._beat_grid = lambda *_: (
                120.0,
                0.81,
                [i * 0.5 for i in range(20)],
            )
            bpm, confidence, beats, source = automix._primary_beat_grid(onset, hop)
        finally:
            automix._beat_grid = original_native

        self.assertAlmostEqual(bpm, 120.0, delta=0.01)
        self.assertAlmostEqual(confidence, 0.81, delta=0.01)
        self.assertEqual(source, "orb-legacy-fallback")
        self.assertGreaterEqual(len(beats), 16)
        self.assertFalse(hasattr(automix, "_librosa_beat_grid"))

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

    def test_reference_long_runway_aligns_b_impact_with_a_release(self) -> None:
        a = track(bpm=128.0, key="C major", vocal=0.65, duration=120.0)
        b = track(bpm=128.0, key="A minor", vocal=0.08, duration=160.0)

        # A stays foreground until 107 s, then genuinely releases with 13 s of tail.
        for index, point in enumerate(a["energyCurve"]):
            if point["time"] < 107.0:
                point["energy"] = 0.74
                a["vocalActivityMask"][index] = 0.64
            else:
                point["energy"] = 0.20
                a["vocalActivityMask"][index] = 0.08
        a["outroStartTime"] = 100.0
        a["vocalProbability"] = 0.50

        # B has a real low-vocal runway and a strong arrival around 20 s.
        for index, point in enumerate(b["energyCurve"]):
            if point["time"] < 20.0:
                point["energy"] = 0.14
                b["lowEnergyCurve"][index]["energy"] = 0.12
                b["vocalActivityMask"][index] = 0.05
            else:
                point["energy"] = 0.84
                b["lowEnergyCurve"][index]["energy"] = 0.76
                b["vocalActivityMask"][index] = 0.16
        b["introEndTime"] = 20.0
        b["mixInTime"] = 20.0
        b["mixInCandidates"] = [{"time": 20.0, "score": 0.95, "type": "phrase"}]
        b["vocalProbability"] = 0.12

        release, end, _ = automix._release_landmarks(a)
        self.assertAlmostEqual(release, 107.0, delta=2.0)

        best, candidates = automix._remote_plan(a, b)

        # mix-v12 authors the choreography first and labels it afterwards.
        # The reference behaviour is what matters: B starts at its opening,
        # reaches its ~20 s structural arrival at A's release, and has already
        # been coexisting with A for a long runway. Depending on the resulting
        # DSP/gain choreography this may be described as RUNWAY_BLEND,
        # INTRO_BED or INTRO_BRIDGE_FILTER.
        runway = next((
            candidate for candidate in candidates
            if abs(float(candidate.get("incomingCueTime", -999.0))) <= 0.2
            and abs(float(candidate.get("incomingHandoffTime", -999.0)) - 20.0) <= 2.5
            and float(candidate.get("transitionStart", 999.0)) < 95.0
            and (
                float(candidate.get("transitionEnd", 0.0))
                - float(candidate.get("transitionStart", 0.0))
            ) > 24.0
        ), None)
        self.assertIsNotNone(runway)
        assert runway is not None
        self.assertIn(runway["style"], {
            "RUNWAY_BLEND", "INTRO_BED", "INTRO_BRIDGE_FILTER",
            "DJ_BLEND", "DJ_FILTER", "EQ_SWAP",
        })
        self.assertNotEqual(best["style"], "NO_TRANSITION")
        self.assertGreaterEqual(
            float(best["selectionScore"]),
            float(runway["selectionScore"]),
        )

    def test_phrase_takeover_can_leave_a_tail_unplayed(self) -> None:
        a = track(bpm=122.0, key="C major", vocal=0.55, duration=120.0)
        b = track(bpm=123.0, key="A minor", vocal=0.45, duration=150.0)

        for index, point in enumerate(a["energyCurve"]):
            if point["time"] < 108.0:
                point["energy"] = 0.70
                a["vocalActivityMask"][index] = 0.58
            else:
                point["energy"] = 0.18
                a["vocalActivityMask"][index] = 0.08
        a["outroStartTime"] = 104.0

        # B is assertive almost immediately, so there is no long quiet runway.
        b["mixInTime"] = 1.0
        b["mixInCandidates"] = [{"time": 1.0, "score": 0.95, "type": "phrase"}]
        for index, point in enumerate(b["energyCurve"]):
            point["energy"] = 0.70
            b["lowEnergyCurve"][index]["energy"] = 0.60
            b["vocalActivityMask"][index] = 0.45

        release, end, _ = automix._release_landmarks(a)
        self.assertAlmostEqual(release, 108.0, delta=2.0)

        best, candidates = automix._remote_plan(a, b)

        takeover = next((
            candidate for candidate in candidates
            if candidate["style"] in {"PHRASE_TAKEOVER", "FOREGROUND_TAKEOVER"}
            and float(candidate.get("incomingCueTime", 999.0)) <= 2.0
        ), None)
        self.assertIsNotNone(takeover)
        assert takeover is not None

        # In mix-v12 "takeover" describes foreground ownership, not necessarily
        # where A is hard-stopped. A may remain audible as a deliberate tail.
        # Validate that B becomes foreground before A's content end rather than
        # requiring transitionEnd itself to truncate A.
        transition_start = float(takeover["transitionStart"])
        transition_end = float(takeover["transitionEnd"])
        handoff_fraction = float(takeover.get("handoffFraction", 1.0))
        foreground_handoff = transition_start + (
            transition_end - transition_start
        ) * handoff_fraction
        self.assertLess(foreground_handoff, 120.0)
        self.assertLessEqual(float(takeover["incomingCueTime"]), 2.0)
        self.assertNotEqual(best["style"], "NO_TRANSITION")
        self.assertGreaterEqual(
            float(best["selectionScore"]),
            float(takeover["selectionScore"]),
        )

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

    def test_instrumental_bed_families_are_available_remotely(self) -> None:
        a = track(bpm=128.0, key="C major", vocal=0.72, duration=180.0)
        b = track(bpm=128.0, key="A minor", vocal=0.16, duration=180.0)

        # A stays active to the end while B exposes a long, low-vocal intro
        # followed by a sustained energy/bass arrival.
        for index, point in enumerate(a["energyCurve"]):
            if point["time"] >= 100.0:
                point["energy"] = 0.84
                a["vocalActivityMask"][index] = 0.72
        a["vocalProbability"] = 0.72

        for index, point in enumerate(b["energyCurve"]):
            if point["time"] < 56.0:
                point["energy"] = 0.18
                b["lowEnergyCurve"][index]["energy"] = 0.14
                b["vocalActivityMask"][index] = 0.08
            else:
                point["energy"] = 0.86
                b["lowEnergyCurve"][index]["energy"] = 0.78
                b["vocalActivityMask"][index] = 0.18
        b["introEndTime"] = 56.0
        b["mixInTime"] = 56.0
        b["vocalProbability"] = 0.16

        _, candidates = automix._remote_plan(a, b)
        styles = {candidate["style"] for candidate in candidates}

        self.assertTrue(
            {"INTRO_BED", "INTRO_BRIDGE_FILTER"} & styles,
            f"expected a remote instrumental-bed family, got {styles}",
        )

    def test_foreground_takeover_is_available_remotely(self) -> None:
        a = track(bpm=120.0, key="C major", vocal=0.12, duration=160.0)
        b = track(bpm=121.0, key="A minor", vocal=0.58, duration=160.0)

        for index, point in enumerate(a["energyCurve"]):
            if point["time"] >= 145.0:
                point["energy"] = 0.28
                a["vocalActivityMask"][index] = 0.08
        a["vocalProbability"] = 0.10

        for index, point in enumerate(b["energyCurve"]):
            if point["time"] <= 8.0:
                point["energy"] = 0.86
                b["vocalActivityMask"][index] = 0.62
        b["introEndTime"] = 4.0
        b["mixInTime"] = 0.0
        b["vocalProbability"] = 0.58

        _, candidates = automix._remote_plan(a, b)
        self.assertTrue(
            any(candidate["style"] == "FOREGROUND_TAKEOVER" for candidate in candidates)
        )

    def test_good_energy_without_structural_anchors_never_becomes_dj_overlap(self) -> None:
        a = track(bpm=128.0, key="C major", vocal=0.10)
        b = track(bpm=129.0, key="A minor", vocal=0.10)

        # Remove every trustworthy bar/phrase anchor while leaving tempo, key,
        # vocal and energy otherwise ideal.
        a["downbeats"] = []
        a["phraseBoundaries"] = []
        a["mixOutCandidates"] = []
        b["downbeats"] = []
        b["phraseBoundaries"] = []
        b["mixInCandidates"] = []

        best, candidates = automix._remote_plan(a, b)
        overlap_styles = {"DJ_BLEND", "DJ_FILTER", "EQ_SWAP"}

        self.assertNotIn(best["style"], overlap_styles)
        self.assertFalse(any(c["style"] in overlap_styles for c in candidates))


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

    def test_unknown_key_allows_only_conservative_filtered_overlap(self) -> None:
        a = track(bpm=128.0, key="C major", vocal=0.10)
        b = track(bpm=129.0, key="", vocal=0.10)
        b["keyConfidence"] = 0.0

        best, candidates = automix._remote_plan(a, b)

        # Missing harmony evidence may use the shorter filtered family when the
        # rhythmic grid is strong and vocals are sparse. It must never authorize
        # an open blend or bass-swap whose harmony cannot be validated.
        self.assertNotEqual(best["style"], "DJ_BLEND")
        self.assertNotEqual(best["style"], "EQ_SWAP")
        self.assertFalse(any(c["style"] in {"DJ_BLEND", "EQ_SWAP"} for c in candidates))
        if best["style"] == "DJ_FILTER":
            self.assertLess(float(best.get("overlapVocalClash", 1.0)), 0.34)
            self.assertGreaterEqual(int(best.get("transitionBeats", 0)), 4)

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

        long_overlap_styles = {
            "RUNWAY_BLEND",
            "INTRO_BED",
            "INTRO_BRIDGE_FILTER",
            "DJ_BLEND",
            "DJ_FILTER",
            "EQ_SWAP",
        }
        if best["style"] in long_overlap_styles:
            # Long overlaps are valid for a protected A only when foreground
            # ownership stays with A until very late in the transition.
            self.assertGreaterEqual(float(best["handoffFraction"]), 0.84)
        else:
            self.assertIn(best["style"], {"PHRASE_TAKEOVER", "PHRASE_CUT", "CUT"})


    def test_heavy_vocal_collision_rejects_long_overlap(self) -> None:
        a = track(bpm=128.0, key="C major", vocal=0.95)
        b = track(bpm=129.0, key="A minor", vocal=0.95)

        best, candidates = automix._remote_plan(a, b)
        overlap_styles = {"DJ_BLEND", "DJ_FILTER", "EQ_SWAP"}

        self.assertNotIn(best["style"], overlap_styles)
        self.assertFalse(any(c["style"] in overlap_styles for c in candidates))


if __name__ == "__main__":
    unittest.main()
