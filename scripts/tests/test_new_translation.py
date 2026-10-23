import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


class NewTranslationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.script = self.root / "scripts/new-translation"
        self.script.parent.mkdir()
        shutil.copyfile(Path(__file__).resolve().parents[1] / "new-translation", self.script)
        self.res = self.root / "app/src/main/res"
        self.defaults = self.res / "values"
        self.defaults.mkdir(parents=True)
        (self.defaults / "strings.xml").write_text(
            '<resources><!-- A label --><string name="title">Title</string>'
            '<string name="brand" translatable="false">Quire</string>'
            '<plurals name="books"><item quantity="one">%d book</item>'
            '<item quantity="other">%d books</item></plurals></resources>'
        )
        (self.defaults / "colors.xml").write_text(
            '<resources><color name="ink">#000000</color></resources>'
        )

    def run_helper(self, qualifier):
        return subprocess.run(
            [sys.executable, str(self.script), qualifier],
            capture_output=True, text=True, check=False,
        )

    def test_scaffold_copies_only_translatable_resources(self):
        for qualifier in ("pt", "pt-rBR", "b+zh+Hans"):
            with self.subTest(qualifier=qualifier):
                result = self.run_helper(qualifier)
                self.assertEqual(result.returncode, 0, result.stderr)
                folder = self.res / f"values-{qualifier}"
                self.assertEqual([p.name for p in folder.iterdir()], ["strings.xml"])
                resources = ET.parse(folder / "strings.xml").getroot()
                self.assertEqual([e.get("name") for e in resources], ["title", "books"])
                self.assertEqual(resources.find("plurals/item[@quantity='other']").text, "%d books")

    def test_existing_locale_is_never_overwritten(self):
        folder = self.res / "values-it"
        folder.mkdir()
        sentinel = folder / "strings.xml"
        sentinel.write_text("Keep my translation")
        result = self.run_helper("it")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(sentinel.read_text(), "Keep my translation")

    def test_bad_qualifiers_cannot_write_outside_locale_folder(self):
        for qualifier in ("../escape", "values-es", "es/../../escape", "es-night", ""):
            with self.subTest(qualifier=qualifier):
                self.assertNotEqual(self.run_helper(qualifier).returncode, 0)
        self.assertEqual([p.name for p in self.res.iterdir()], ["values"])

    def test_parse_failure_leaves_no_partial_locale(self):
        (self.defaults / "z-broken.xml").write_text("<resources>")
        result = self.run_helper("fr")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([p.name for p in self.res.iterdir()], ["values"])


if __name__ == "__main__":
    unittest.main()
