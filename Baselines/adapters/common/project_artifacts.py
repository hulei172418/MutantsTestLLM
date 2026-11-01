"""Project-local artifact layout shared by every Baseline adapter.

Generated test artifacts live beside the evaluated project, while prompts,
model responses, and trace logs remain in ``Baselines/runs``.  The mapping is
written to both locations so later mutant execution never relies on guessing.
"""

from __future__ import print_function

import hashlib
import json
import os
import re
from pathlib import Path


def _safe_segment(value, fallback="item"):
    value = re.sub(r"[^A-Za-z0-9_.-]+", "_", str(value or "")).strip("_.-")
    return (value or fallback)[:100]


def _atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    os.replace(str(temporary), str(path))


def _experiment_run_id(run_dir):
    run_dir = Path(run_dir).resolve()
    if run_dir.parent.name == "targets":
        return _safe_segment(run_dir.parent.parent.name, "run")
    return _safe_segment(run_dir.name, "run")


def _method_id(metadata):
    identity = "|".join(
        (
            str(metadata["project"]),
            str(metadata["class_name"]),
            str(metadata["method_signature"]),
        )
    )
    digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:16]
    return "{0}_{1}".format(
        _safe_segment(str(metadata["class_name"]).rsplit(".", 1)[-1], "class"),
        digest,
    )


class ProjectArtifactLayout(object):
    """Resolve collision-safe source/class/report paths for one method run."""

    def __init__(self, metadata, run_dir, baseline_name):
        self.metadata = dict(metadata)
        self.run_dir = Path(run_dir).resolve()
        self.project_root = Path(metadata["project_root"]).resolve()
        if not self.project_root.is_dir():
            raise FileNotFoundError(
                "Project root does not exist: {0}".format(self.project_root)
            )
        self.baseline_name = _safe_segment(baseline_name, "Baseline")
        self.experiment_run_id = _experiment_run_id(self.run_dir)
        self.method_id = _method_id(metadata)
        self.root = (
            self.project_root
            / "baselines"
            / self.baseline_name
            / "runs"
            / self.experiment_run_id
            / self.method_id
        ).resolve()
        try:
            self.root.relative_to(self.project_root)
        except ValueError:
            raise RuntimeError("Artifact root escaped project root: {0}".format(self.root))

    def round_root(self, round_number):
        return self.root / "rounds" / "{0:03d}".format(int(round_number))

    def round_paths(self, round_number):
        root = self.round_root(round_number)
        return {
            "root": root,
            "src": root / "src",
            "classes": root / "classes",
            "report": root / "report",
        }

    def final_paths(self):
        root = self.root / "final"
        return {
            "root": root,
            "src": root / "src",
            "classes": root / "classes",
            "report": root / "report",
        }

    def initialize(self):
        self.root.mkdir(parents=True, exist_ok=True)
        mapping = {
            "schema_version": 1,
            "baseline": self.baseline_name,
            "experiment_run_id": self.experiment_run_id,
            "method_id": self.method_id,
            "project": self.metadata["project"],
            "project_root": str(self.project_root),
            "class_name": self.metadata["class_name"],
            "method_signature": self.metadata["method_signature"],
            "baseline_run_dir": str(self.run_dir),
            "project_artifact_root": str(self.root),
        }
        _atomic_json(self.root / "index.json", mapping)
        _atomic_json(self.run_dir / "project_artifacts.json", mapping)
        return mapping

