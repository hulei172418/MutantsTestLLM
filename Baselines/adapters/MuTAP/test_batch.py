import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ADAPTER_DIR = Path(__file__).resolve().parent
if str(ADAPTER_DIR) not in sys.path:
    sys.path.insert(0, str(ADAPTER_DIR))

import run_batch as mutap_batch


def _groups():
    groups = {}
    for index in range(1, 6):
        rows = []
        for mutant in range(1, index + 2):
            rows.append(
                {
                    "project": "p",
                    "class_name": "p.C{0}".format(index),
                    "method_signature": "m{0}()".format(index),
                    "source_path": "C{0}.java".format(index),
                    "file_path": "C{0}_M{1}.class".format(index, mutant),
                }
            )
        groups["method-{0}".format(index)] = rows
    return groups


class MuTAPBatchTest(unittest.TestCase):
    def test_step_prepare_selects_five_unique_methods_and_all_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "targets.jsonl"
            manifest.write_text("{}\n", encoding="utf-8")
            batch_dir = root / "batch"
            groups = _groups()

            def fake_prepare(method_manifest, run_dir):
                run_dir = Path(run_dir)
                run_dir.mkdir(parents=True)
                first = json.loads(Path(method_manifest).read_text(encoding="utf-8").splitlines()[0])
                (run_dir / "metadata.json").write_text(
                    json.dumps(
                        {
                            "project": first["project"],
                            "class_name": first["class_name"],
                            "method_signature": first["method_signature"],
                        }
                    ),
                    encoding="utf-8",
                )
                (run_dir / "state.json").write_text(
                    json.dumps({"state": "PREPARED_API_NOT_CALLED"}), encoding="utf-8"
                )
                (run_dir / "calls").mkdir()
                return {"state": "PREPARED_API_NOT_CALLED"}

            with patch.object(mutap_batch, "load_method_groups", return_value=groups), patch.object(
                mutap_batch, "prepare", side_effect=fake_prepare
            ):
                result = mutap_batch.prepare_batch(manifest, 5, batch_dir)

            self.assertEqual(5, result["selected_method_count"])
            self.assertEqual(sum(len(rows) for rows in groups.values()), result["selected_source_record_count"])
            for index, rows in enumerate(groups.values(), 1):
                saved = (batch_dir / "manifests" / "{0:03d}.jsonl".format(index)).read_text(
                    encoding="utf-8"
                ).splitlines()
                self.assertEqual(len(rows), len(saved))

            def fake_run(index, total, run_dir, mutant_workers, survivor_seed):
                self.assertEqual(5, total)
                return {"index": index, "status": "SUCCEEDED", "run_dir": str(run_dir)}

            with patch.object(mutap_batch, "_run_prepared_target", side_effect=fake_run) as runner, patch.object(
                mutap_batch, "summarize_batch", return_value={"formal_score_valid": True}
            ), patch.object(mutap_batch, "load_config", return_value={"thread_count": 5}):
                summary = mutap_batch.run_prepared_batch(batch_dir, workers=5, mutant_workers=4)

            self.assertEqual(5, runner.call_count)
            self.assertEqual(5, summary["selected_method_count"])
            self.assertEqual(5, summary["succeeded_method_count"])

    def test_one_click_batch_also_uses_five_unique_methods(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "targets.jsonl"
            manifest.write_text("{}\n", encoding="utf-8")
            batch_dir = root / "one-click"
            groups = _groups()

            def fake_target(index, total, records, manifests_dir, targets_dir, mutant_workers, survivor_seed):
                self.assertEqual(5, total)
                self.assertEqual(index + 1, len(records))
                return {"index": index, "status": "SUCCEEDED"}

            with patch.object(mutap_batch, "load_method_groups", return_value=groups), patch.object(
                mutap_batch, "_run_target", side_effect=fake_target
            ) as runner, patch.object(
                mutap_batch, "summarize_batch", return_value={"formal_score_valid": True}
            ), patch.object(mutap_batch, "load_config", return_value={"thread_count": 5}):
                summary = mutap_batch.run_batch(
                    manifest=manifest,
                    limit=5,
                    batch_dir=batch_dir,
                    workers=5,
                    mutant_workers=4,
                    survivor_seed=42,
                )

            self.assertEqual(5, runner.call_count)
            self.assertEqual(5, summary["selected_method_count"])
            metadata = json.loads((batch_dir / "batch_metadata.json").read_text(encoding="utf-8"))
            self.assertEqual(
                sum(len(rows) for rows in groups.values()),
                metadata["selected_source_record_count"],
            )

    def test_interrupted_batch_is_not_formally_scoreable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "targets" / "001"
            target.mkdir(parents=True)
            (target / "state.json").write_text(
                json.dumps({"state": "CALLING_AUGMENTATION", "round": 3}),
                encoding="utf-8",
            )
            (root / "batch_metadata.json").write_text(
                json.dumps(
                    {
                        "batch_state": "RUNNING",
                        "targets": [{"index": 1, "run_dir": str(target)}],
                    }
                ),
                encoding="utf-8",
            )
            mutap_batch.mark_batch_interrupted(root, "test stop")
            metadata = json.loads((root / "batch_metadata.json").read_text(encoding="utf-8"))
            self.assertEqual("INTERRUPTED", metadata["batch_state"])
            self.assertFalse(metadata["formal_score_valid"])
            state = json.loads((target / "state.json").read_text(encoding="utf-8"))
            self.assertEqual("INTERRUPTED", state["state"])
            self.assertFalse(state["formal_score_valid"])


if __name__ == "__main__":
    unittest.main()
