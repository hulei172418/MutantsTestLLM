"""Canonical workbook and target-manifest paths shared by every baseline."""

from pathlib import Path


COMMON_DIR = Path(__file__).resolve().parent
BASELINES_DIR = COMMON_DIR.parents[1]
DATA_DIR = BASELINES_DIR / "data"

DEFAULT_MUTANT_WORKBOOK = DATA_DIR / "mutant_total.xlsx"
DEFAULT_TARGET_MANIFEST = DATA_DIR / "target_manifest.jsonl"
FIRST_TEN_TARGET_MANIFEST = DATA_DIR / "target_manifest.first10.jsonl"

