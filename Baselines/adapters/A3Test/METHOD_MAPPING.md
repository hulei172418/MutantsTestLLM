# A3Test Paper–Author Code–DeepSeek Adaptation Mapping

| Original method element | Paper/author code | Current adaptation | Conclusion |
|---|---|---|---|
| Assertion knowledge | PLBART is pretrained on Atlas assertion data for assertion-oriented knowledge | The DeepSeek prompt explicitly requires meaningful assertions derived from focal behavior | This is a model substitution and must not be presented as a reproduction of the PLBART weights |
| Test generation | `fm+fc+c+m+f` is used as the source and a complete test case as the target | `context.py` preserves the focal method, focal class, constructor signatures, signatures of other public methods, and public fields | The context fields are faithful |
| Inference candidates | Beam search retains candidates internally, but only the highest-probability test is output for each focal method | Each method invokes `a3test_generation` once with temperature 0 and requests one complete test | Single-best-candidate adaptation |
| Assertion stage | Assertion knowledge resides in the pretrained weights; it is not a second inference-time call | There is no second `a3test_assertion` stage | This corrects the erroneous two-stage design in the old plan |
| Postprocessing | `postProcessingScript.py` adds `@Test`, public/void/test signatures, and repairs unbalanced braces | `verify_and_wrap` applies equivalent deterministic corrections and records before/after audit data | This is not counted as an LLM call or repair |
| Repair | The paper and author inference scripts do not perform compilation- or runtime-feedback repair | Compilation or original-program execution failure immediately terminates the target as an invalid test | No additional LLM repair is allowed |
| Iteration | The main experiment generates once per focal method; a separate six-run multiple-iteration comparison is reported | Default and formal entry points are fixed to one generation | Multiple generations may only be used as a separate sensitivity experiment |
| Correctness | Compilation, JUnit execution, focal coverage, and line/branch coverage | Common Java pipeline and JaCoCo | Coverage does not trigger model calls |
| Mutation Score | The original work executes mutants only with tests that pass on the original program | Common isolated executor; the overall report includes both End-to-End MS and Executable MS | Mutation information does not enter the generation prompt |

There is no native `a3test_skeleton → a3test_assertion → a3test_repair` inference chain. Implementing it as three LLM calls would turn A3Test into a new method, so this adapter explicitly does not use it.
