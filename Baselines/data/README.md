# Baseline target manifests

With `Baselines` opened as the working folder, running
`adapters/common/build_target_manifest.py` without arguments uses:

```text
input:         data/mutant_total.xlsx
programs root: E:/PHD/testJava/Programs
output:        data/target_manifest.jsonl
```

`target_manifest.jsonl` is the frozen full comparison set generated from
`data/mutant_total.xlsx`. Manifest schema version 3 preserves the twelve original Excel
columns at the top level of every line:

```text
operator, line, method, class, class_f, mutation_statement,
package, project, file_path, original_graph_path,
mutant_graph_path, equivalent
```

Normalized execution aliases may be added, but never replace these fields. Each
line represents one mutant; runners that generate one suite per focal method
group rows by:

```text
project + class_name + method_signature
```

Generate the full manifest only when the input workbook and experiment set are
ready to freeze:

```powershell
python -B adapters/common/build_target_manifest.py `
  --input data/mutant_total.xlsx `
  --programs-root E:/PHD/testJava/Programs `
  --output data/target_manifest.jsonl
```

For development, use a separate bounded manifest. This avoids accidentally
treating a smoke subset as the frozen experiment set:

```powershell
python -B adapters/common/build_target_manifest.py `
  --input data/mutant_total.xlsx `
  --programs-root E:/PHD/testJava/Programs `
  --project commons-csv-1.2 `
  --class-name org.apache.commons.csv.Token `
  --method-signature "void_reset()" `
  --output data/target_manifest.smoke.jsonl
```

Every output has a sibling `*.summary.json` recording input/output SHA-256,
filters, method count, mutant count, and path-resolution counts. Classpaths are
resolved at run time from each project's Maven build; they are intentionally not
copied from one developer machine into the frozen manifest.

Grouping suppresses duplicate model generation, not mutant records. One method
call is linked back to all grouped rows through `call_mutant_trace.jsonl`.

`adapters/common/manifest_paths.py` is the single path source for all nine
baseline adapters. Their no-argument/default formal input is always the full
`data/target_manifest.jsonl`. Bounded files such as `target_manifest.first10.jsonl`
are passed explicitly by VS Code smoke/batch configurations; they are never a
baseline-specific hidden default.

Workbook package-like fields may include source-root markers such as
`java.org.apache...` for a project laid out under `src/java`. Manifest building
removes only the known leading source-root markers (`main.java.`, `main.`,
`test.java.`, `test.`, and `java.`); it does not rewrite real prefixes such as
`javax.`. This keeps `class_name` equal to the JVM binary name.

`target_manifest.commons-jxpath-1.3.first5.jsonl` is a bounded EvoSuite
validation manifest. It contains the first five unique
`project + class_name + method_signature` groups for `commons-jxpath-1.3` and
all 549 workbook rows associated with those five methods. After physical-mutant
deduplication the evaluation denominator is 346.
