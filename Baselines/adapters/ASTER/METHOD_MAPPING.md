# ASTER method mapping

Implementation name: `ASTER-Java-DS-Reimplementation`.

This is an independent Java reimplementation from the paper and public
artifact, with every LLM call routed through the shared DeepSeek-V4-Flash
gateway. It is not an official-source rerun.

| Paper component | Adapter implementation | Evidence/status |
|---|---|---|
| Testing scope | Manifest-selected focal method | The shared experiment unit is one deduplicated focal method. |
| Relevant constructors | `context.py` | Focal constructors plus transitive application-parameter constructors. |
| Relevant auxiliary methods | `context.py` | Setters for focal/parameter state; getters for focal/return assertions. |
| Private-method paths | `context.py` | Direct focal-to-private call chains are recorded; unresolved deeper call-graph edges are not invented. |
| Mocking candidates | `context.py`, `aster.properties` | Only user-configured API prefixes may create mock fields; default is empty. |
| Test generation prompt | `aster_initial` | Mirrors Figure 3 base/generation template and supplies a partial test skeleton. |
| Output sanitization | `normalize_test_source` | Removes prose/fences, normalizes package/class and JUnit 4; no behavioral repair. |
| Error remediation | `aster_repair` | Real javac or original-program JUnit feedback; compile and runtime failures are both eligible. |
| Coverage augmentation | `aster_coverage` | The current passing suite and uncovered focal-method lines are sent directly to the model. |
| Mutation score | shared `mutant_execution.py` | Blind final evaluation only; no mutation data is visible to ASTER prompts. |

## Disclosed versus unresolved controls

The paper establishes iterative repair and iterative coverage augmentation but
does not publish a numeric repair bound, a numeric coverage target, temperature,
or a complete engine. The public artifact stores one initial and one coverage
suite per class. Consequently, adapter defaults are explicit reimplementation
bounds:

- repair calls: at most 3 after each generated candidate;
- coverage calls: at most 1;
- focal measurable line target: 100%;
- temperature: 0 for deterministic cross-baseline DeepSeek evaluation;
- output ceiling: 16000 for every stage.

These values are configurable in `aster.properties` and are written into every
run's `metadata.json`; they are not labelled as native ASTER parameters.

JUnit output in the artifact includes both JUnit 4 and JUnit 5 projects. The
common evaluator requires JUnit 4, so deterministic JUnit 5-to-4 import and
annotation normalization is recorded as an evaluation adaptation.
