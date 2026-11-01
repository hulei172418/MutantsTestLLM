"""Execute a previously prepared MuTAP batch without preparing it again."""

from __future__ import print_function

import argparse
import json

from run_batch import mark_batch_interrupted, run_prepared_batch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batch-dir", required=True)
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--mutant-workers", type=int, default=0)
    parser.add_argument("--survivor-seed", type=int, default=42)
    args = parser.parse_args()
    try:
        result = run_prepared_batch(
            args.batch_dir, args.workers, args.mutant_workers, args.survivor_seed
        )
    except KeyboardInterrupt:
        mark_batch_interrupted(
            args.batch_dir, "prepared batch interrupted from CLI/F5"
        )
        raise
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
