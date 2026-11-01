"""Shared terminal-state classification for Baseline pipeline exceptions."""

from __future__ import print_function


def is_model_provider_failure(error):
    """Whether the common gateway exhausted or rejected a provider request."""
    return str(error).startswith("LLM API failed after ")


def classify_pipeline_failure(error, existing_state=None):
    """Map an exception to a non-scoring, auditable terminal state.

    Generated-output truncation remains a model-output outcome.  Failures
    after original-program admission belong to mutation infrastructure.
    Provider/network exhaustion is infrastructure and must not lower the
    Baseline's End-to-End mutation score.  Unknown implementation failures
    invalidate the experiment without being mislabeled as network failures.
    """
    if type(error).__name__ == "ModelOutputTruncatedError":
        return "MODEL_OUTPUT_TRUNCATED"
    if type(error).__name__ == "ModelOutputInvalidError":
        return "MODEL_OUTPUT_INVALID"
    if existing_state == "ORIGINAL_PASSED":
        return "MUTATION_INFRASTRUCTURE_FAILED"
    if is_model_provider_failure(error):
        return "MODEL_PROVIDER_FAILED"
    return "PIPELINE_FAILED"
