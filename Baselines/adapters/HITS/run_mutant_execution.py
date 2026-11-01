"""Run the shared isolated MuJava evaluator for one accepted HITS target."""

from __future__ import print_function

import argparse
import json
import sys
from pathlib import Path


COMMON_DIR = Path(__file__).resolve().parents[1] / "common"
if str(COMMON_DIR) not in sys.path:
    sys.path.insert(0, str(COMMON_DIR))

from mutant_execution import execute_mutants


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--max-mutants", type=int, default=0)
    args = parser.parse_args()
    print(
        json.dumps(
            execute_mutants(
                args.run_dir, max_mutants=args.max_mutants, workers=args.workers
            ),
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
