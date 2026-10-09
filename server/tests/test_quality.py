import unittest

from app import quality as q


class BuildLadderTest(unittest.TestCase):
    def test_prefers_h264_over_vp9_for_the_same_shape(self):
        videos = [
            q.VideoCandidate(height=1080, fps=30, codec="vp9"),
            q.VideoCandidate(height=1080, fps=30, codec="avc1"),
        ]
        ladder = q.build_ladder(videos, [])
        # "Mejor" + una sola entrada para 1080p30 (deduplicada por forma).
        video_entries = [e for e in ladder if not e.is_audio_only]
        self.assertEqual(len(video_entries), 2)
        self.assertEqual(video_entries[0].id, q.BEST_ID)
        self.assertEqual(video_entries[1].video_codec, "avc1")

    def test_orders_desc_and_labels_high_fps(self):
        videos = [
            q.VideoCandidate(height=360, fps=30, codec="avc1"),
            q.VideoCandidate(height=1080, fps=60, codec="avc1"),
            q.VideoCandidate(height=720, fps=30, codec="avc1"),
        ]
        ladder = [e for e in q.build_ladder(videos, []) if not e.is_audio_only]
        labels = [e.label for e in ladder]
        self.assertEqual(labels, [q.BEST_LABEL, "1080p60", "720p", "360p"])

    def test_keeps_several_shapes_of_the_same_height(self):
        videos = [
            q.VideoCandidate(height=1080, fps=30, codec="avc1"),
            q.VideoCandidate(height=1080, fps=60, codec="avc1"),
        ]
        ids = [e.id for e in q.build_ladder(videos, []) if not e.is_audio_only]
        self.assertIn(q.video_quality_id(1080, 30), ids)
        self.assertIn(q.video_quality_id(1080, 60), ids)

    def test_ignores_formats_without_height(self):
        videos = [q.VideoCandidate(height=0, fps=30, codec="avc1")]
        ladder = q.build_ladder(videos, [])
        self.assertEqual(ladder, [])

    def test_audio_is_limited_and_sorted_desc(self):
        audios = [q.AudioCandidate(abr=abr, codec="mp4a") for abr in (48, 64, 96, 128, 160, 256)]
        ladder = q.build_ladder([], audios)
        self.assertEqual(len(ladder), q.MAX_AUDIO_OPTIONS)
        self.assertEqual([e.id for e in ladder], [
            q.audio_quality_id(256),
            q.audio_quality_id(160),
            q.audio_quality_id(128),
            q.audio_quality_id(96),
        ])
        self.assertTrue(all(e.is_audio_only for e in ladder))
        self.assertTrue(all(e.format == "m4a" for e in ladder))

    def test_empty_input_gives_empty_ladder(self):
        self.assertEqual(q.build_ladder([], []), [])


class ParseQualityIdTest(unittest.TestCase):
    def test_best(self):
        self.assertEqual(q.parse_quality_id("best"), ("best", None))

    def test_video_roundtrip(self):
        tag, spec = q.parse_quality_id(q.video_quality_id(1080, 60))
        self.assertEqual(tag, "video")
        self.assertEqual((spec.height, spec.fps), (1080, 60))

    def test_audio_roundtrip(self):
        tag, spec = q.parse_quality_id(q.audio_quality_id(160))
        self.assertEqual(tag, "audio")
        self.assertEqual(spec.abr, 160)

    def test_unknown_id_raises(self):
        for bad in ("", "v:1080", "a:1:2", "x:1", "v:abc:30"):
            with self.assertRaises(ValueError):
                q.parse_quality_id(bad)


class SelectorTest(unittest.TestCase):
    def test_best_video_prefers_mp4_m4a(self):
        self.assertEqual(
            q.selector_for("best", "video"),
            "bv*[vcodec^=avc1][ext=mp4]+ba[ext=m4a]/bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b",
        )

    def test_video_selector_prefers_h264_in_mp4(self):
        selector = q.selector_for(q.video_quality_id(1080, 60), "video")
        self.assertIn("[vcodec^=avc1][ext=mp4]", selector)

    def test_video_selector_uses_height_and_fps(self):
        selector = q.selector_for(q.video_quality_id(1080, 60), "video")
        self.assertIn("[height=1080]", selector)
        self.assertIn("[fps<=60]", selector)

    def test_video_without_fps_omits_fps_clause(self):
        selector = q.selector_for(q.video_quality_id(720, 0), "video")
        self.assertIn("[height=720]", selector)
        self.assertNotIn("[fps<=", selector)

    def test_audio_selector_caps_abr(self):
        selector = q.selector_for(q.audio_quality_id(128), "audio")
        self.assertTrue(selector.startswith("ba[abr<=128][ext=m4a]"))

    def test_audio_best(self):
        self.assertEqual(q.selector_for("best", "audio"), "ba[ext=m4a]/ba")

    def test_mismatched_kind_raises(self):
        with self.assertRaises(ValueError):
            q.selector_for(q.video_quality_id(1080, 30), "audio")
        with self.assertRaises(ValueError):
            q.selector_for(q.audio_quality_id(128), "video")

    def test_unknown_kind_raises(self):
        with self.assertRaises(ValueError):
            q.selector_for("best", "banana")


if __name__ == "__main__":
    unittest.main()
