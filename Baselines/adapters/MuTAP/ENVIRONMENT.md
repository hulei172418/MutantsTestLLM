# MuTAP Environment

- Java: the project's current JDK (frozen to JDK 8 by the master plan)
- Test framework: JUnit 4 on the project classpath
- Model: DeepSeek-V4-Flash
- Temperature: 0.8 (the original Codex setting)
- Maximum output per call in the primary comparison: 16,000 tokens
- Default survivor seed: 42
- Syntax-repair limit: 10
- Generation concurrency: `--workers`; mutant-execution concurrency: `--mutant-workers`

The API key, provider, model, and network parameters belong only in `configs/llm.properties`. MuTAP's three-stage token and temperature parameters belong only in the `llm.properties` file in this directory. No README, prompt, response, or run artifact may copy the API key.
