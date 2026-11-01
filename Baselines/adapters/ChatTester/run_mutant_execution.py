"""Run the accepted ChatTester suite against its mapped MuJava mutants."""

from __future__ import print_function

import argparse
import json
import sys
from pathlib import Path


ADAPTER_DIR = Path(__file__).resolve().parent
BASELINES_DIR = ADAPTER_DIR.parents[1]
COMMON_DIR = BASELINES_DIR / "adapters" / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from mutant_execution import execute_mutants


DEFAULT_RUN_DIR = (
    BASELINES_DIR / "runs" / "20261029094445938_chattester_batch_001"
    / "targets" / "010_Date_void_setMillis_long"
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", default=str(DEFAULT_RUN_DIR))
    parser.add_argument("--max-mutants", type=int, default=0)
    parser.add_argument("--execution-id", default="")
    parser.add_argument(
        "--workers",
        type=int,
        default=0,
        help="parallel isolated-JVM workers; 0 uses java.mutant.workers",
    )
    args = parser.parse_args()
    summary = execute_mutants(
        args.run_dir,
        max_mutants=args.max_mutants,
        execution_id=args.execution_id or None,
        workers=args.workers,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
