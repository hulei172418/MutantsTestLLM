# IntUT method mapping

Implementation identity: `IntUT-DS-Reimplementation`.

| Paper component | Local implementation | Evidence and status |
|---|---|---|
| PAINT block analysis | `analysis.py` | Preserves if/loop/switch/catch branch sites; independent lexical approximation because the original CFG engine is unavailable. |
| SSM-DFS condition chains | `build_intentions` | Retains lexical nesting and prunes parallel combinations; records that this is not bytecode-equivalent. |
| Input constraints | `build_intentions` | Maps condition expressions mentioning focal parameters into the input section. |
| Mock classifier | `rag.py`, `analysis.py` | Target-project historical mock evidence fallback; original CodeBERT weights/data are unavailable. |
| Test intention template | `build_intentions` | Input, mock, hint, and expected-result sections from Figure 5. |
| Unit-test RAG | `rag.py` | Reconstructs historical focal-test pairs from project test invocations, then compares current and historical focal methods at Jaccard threshold 0.8. |
| Mock-example RAG | `rag.py` | Retrieves target-project tests containing mock statements. |
| Final prompt | `protocol.py` | Four ordered components from Figure 6. |
| Model | shared gateway | Original CodeLLaMA/LLaMA is replaced by mandatory DeepSeek-V4-Flash. |
| Multiple candidates | `run_pipeline.py` | Five independent DeepSeek calls per intention; API adaptation of five samples/request. |
| Developer selection | `run_pipeline.py` | First original-passing candidate is the deterministic automation proxy. |
| Post-processing/recovery | none | Deliberately absent: paper leaves recovery to future work. |
| Original-program validation | common `JavaTestPipeline` | Evaluation gate only; compiler/JUnit feedback is not sent to the model. |
| Line/branch coverage | common `jacoco_coverage.py` | Post-selection focal-method report only; never triggers generation or candidate selection. |
| Mutation evaluation | common `mutant_execution.py` | Never visible to IntUT generation or selection. |

Undisclosed maximum executed functions, call-recursion depth, and intention
safety cap are isolated in `intut.properties` and copied into run metadata.

