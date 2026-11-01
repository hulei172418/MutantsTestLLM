"""Prepare five unique MuTAP methods for step-by-step VS Code execution."""

from __future__ import print_function

import argparse
import json

from prepare_run import DEFAULT_MANIFEST
from run_batch import prepare_batch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument("--limit", type=int, default=5)
    parser.add_argument("--batch-dir", required=True)
    args = parser.parse_args()
    print(
        json.dumps(
            prepare_batch(args.manifest, args.limit, args.batch_dir),
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
