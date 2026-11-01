# IntUT source evidence

- Paper: *Test Intention Guided LLM-based Unit Test Generation*, ICSE 2025,
  DOI `10.1109/ICSE55347.2025.00243`.
- Local paper: `ReferencePapers/Test Intention Guided LLM-based Unit Test Generation.pdf`.
- Public-code status checked again on 2026-10-29: no verifiable repository
  belonging to the paper authors and no released IntUT IntelliJ plugin, PAINT
  CFG implementation, CodeBERT mock-classifier weights, or experiment package
  was found.

Consequently there is no `sources/IntUT` checkout to pretend is author code.
All new executable work is confined to `adapters/IntUT`. If an official source
is published later, it must be frozen under `sources/IntUT` unchanged and
compared against this independent implementation before replacing it.

Paper-derived protocol facts used here:

1. PAINT performs CFG block analysis, mock decisions, and SSM-DFS condition
   chain extraction.
2. A test intention has input parameters, mock behavior, an optional hint, and
   an expected result.
3. The final prompt has program context, RAG examples, test prefix/existing
   tests, and one test intention.
4. RAG is dynamically built from the developer's current project; historical
   focal-test pairs are reconstructed from test invocations and same-name
   production methods, then unit-test retrieval uses focal-to-historical-focal
   Jaccard similarity threshold 0.8.
5. IntUT presents multiple candidates; the reported ablation generates five
   candidates per request.
6. The paper uses PowerMock and a trained CodeBERT mock classifier, but neither
   implementation nor weights are public.
7. The paper does not implement automated repair; recovery is future work.

