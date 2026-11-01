import sys
import unittest
from pathlib import Path


COMMON_DIR = Path(__file__).resolve().parent.parent / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from java_test_pipeline import _prefer_junit4  # noqa: E402


class JavaPipelineClasspathTest(unittest.TestCase):
    def test_junit3_is_removed_when_junit4_exists(self):
        values = [
            "C:/project/classes",
            "C:/lib/junit-3.8.2.jar",
            "C:/lib/junit-4.13.1.jar",
            "C:/lib/hamcrest-core-1.3.jar",
        ]
        normalized = _prefer_junit4(values)
        self.assertNotIn("C:/lib/junit-3.8.2.jar", normalized)
        self.assertIn("C:/lib/junit-4.13.1.jar", normalized)

    def test_junit3_is_preserved_without_junit4(self):
        values = ["C:/lib/junit-3.8.2.jar"]
        self.assertEqual(values, _prefer_junit4(values))


if __name__ == "__main__":
    unittest.main()
