# A3Test Source Pin

- Paper: *A3Test: Assertion-Augmented Automated Test Case Generation*.
- Author repository: <https://github.com/awsm-research/A3Test>.
- Alternative entry point: <https://github.com/A3Test-TestCaseGeneration/A3Test>.
- Local read-only snapshot: `Baselines/sources/A3Test`.
- Pinned commit: `14fa...`.

The author repository contains PLBART training/inference code, Methods2Test data, Defects4J method lists, and `postProcessingScript.py`, but it does not include the trained model weights used in the paper. The paper also provides a Hugging Face checkpoint. Under this project's unified-model rule, the primary comparison does not run the PLBART checkpoint and instead uses DeepSeek-V4-Flash; therefore, the implementation must be named `A3Test-DS-Adapted`.

Keep `sources/A3Test` Git-clean. Do not install dependencies, train, run inference, or generate files there. All adaptation code belongs in `adapters/A3Test`.
