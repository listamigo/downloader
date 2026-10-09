import unittest

from app.naming import MAX_STEM_LENGTH, safe_filename


class SafeFilenameTest(unittest.TestCase):
    def test_appends_extension(self):
        self.assertEqual(safe_filename("Cancion", "m4a"), "Cancion.m4a")

    def test_strips_path_separators_and_unsafe_chars(self):
        self.assertEqual(
            safe_filename('a/b\\c:d*e?f"g<h>i|j', "mp4"),
            "a_b_c_d_e_f_g_h_i_j.mp4",
        )

    def test_removes_control_characters(self):
        self.assertEqual(safe_filename("a\x00b\x1fc", "mp4"), "a_b_c.mp4")

    def test_collapses_whitespace_and_trims_dots(self):
        self.assertEqual(safe_filename("  hola   mundo .. ", "mp4"), "hola mundo.mp4")

    def test_empty_title_falls_back(self):
        self.assertEqual(safe_filename("   ", "mp4"), "media.mp4")

    def test_length_is_bounded(self):
        name = safe_filename("x" * 500, "mp4")
        self.assertEqual(len(name), MAX_STEM_LENGTH + len(".mp4"))

    def test_extension_is_normalized(self):
        self.assertEqual(safe_filename("a", ".MP4"), "a.mp4")


if __name__ == "__main__":
    unittest.main()
