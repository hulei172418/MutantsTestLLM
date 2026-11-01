# MuTAP Reproduction Commands

Open VS Code at `E:\PHD\MutantsTestLLM\Baselines`.

## Step-by-Step Execution for Five Methods

1. Press F5 and select `MuTAP 1. Prepare Step-by-Step Batch (5 Methods)`; enter a new batch directory that does not exist.
2. Press F5 and select `MuTAP 2. Run Prepared Batch End-to-End (5 Methods)`; enter exactly the same directory.

Equivalent commands:

```powershell
python -B adapters/MuTAP/prepare_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 5 `
  --batch-dir runs/20260318_mutap_step5_manual_002

python -B adapters/MuTAP/run_prepared_batch.py `
  --batch-dir runs/20260318_mutap_step5_manual_002 `
  --workers 5 `
  --mutant-workers 4 `
  --survivor-seed 42
```

## Complete Batch for the First Five Deduplicated Methods

```powershell
python -B adapters/MuTAP/run_batch.py `
  --manifest data/target_manifest.first10.jsonl `
  --limit 5 `
  --batch-dir runs/20260318_mutap_batch_001 `
  --workers 5 `
  --mutant-workers 4 `
  --survivor-seed 42
```

`--limit 5` means the first five deduplicated focal methods. Every unique physical mutant associated with each method is executed. The step-by-step and one-click entry points invoke the same `prepare`, `run_pipeline`, and common summarization logic; the only difference is whether execution pauses between two F5 launches so that the prepared artifacts for all five targets can be inspected.

Interruption rule: strict MuTAP may separately invoke the model for many survivors on large methods and rerun every mutant after each augmentation. If Ctrl+C is pressed in VS Code or a terminal, the interrupted batch is automatically marked `INTERRUPTED` with `formal_score_valid=false`. It cannot be resumed or scored; subsequent experiments must use a new directory. Formal results are read from `batch_summary.json`, each target's `mutap_result.json`, `calls/`, and `mutant_execution/latest.json`.
