package baselines.common.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.tools.ExecFileLoader;
import org.json.JSONArray;
import org.json.JSONObject;

/** Converts one JaCoCo exec file and the exact runtime class into line/branch coverage. */
public final class JacocoLineCoverageCli {
    private JacocoLineCoverageCli() {
    }

    public static void main(String[] args) throws Exception {
        String exec = required(args, "--exec");
        String classFile = required(args, "--class-file");
        String className = required(args, "--class-name");
        String output = required(args, "--output");

        ExecFileLoader loader = new ExecFileLoader();
        loader.load(new File(exec));
        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
        analyzer.analyzeAll(new File(classFile));

        String vmName = className.replace('.', '/');
        IClassCoverage selected = null;
        for (IClassCoverage coverage : builder.getClasses()) {
            if (vmName.equals(coverage.getName())) {
                selected = coverage;
                break;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("No JaCoCo coverage for class " + className);
        }
        boolean classIdMatch = !builder.getNoMatchClasses().contains(selected);

        int firstLine = Math.max(selected.getFirstLine(), optionalInt(args, "--start-line", selected.getFirstLine()));
        int lastLine = Math.min(selected.getLastLine(), optionalInt(args, "--end-line", selected.getLastLine()));
        if (firstLine > lastLine) {
            throw new IllegalArgumentException("Invalid coverage line range: " + firstLine + ".." + lastLine);
        }

        List<Integer> covered = new ArrayList<Integer>();
        List<Integer> missed = new ArrayList<Integer>();
        int coveredBranches = 0;
        int missedBranches = 0;
        int coveredInstructions = 0;
        int missedInstructions = 0;
        for (int line = firstLine; line <= lastLine; line++) {
            ICounter instructions = selected.getLine(line).getInstructionCounter();
            ICounter branches = selected.getLine(line).getBranchCounter();
            coveredBranches += branches.getCoveredCount();
            missedBranches += branches.getMissedCount();
            coveredInstructions += instructions.getCoveredCount();
            missedInstructions += instructions.getMissedCount();
            if (instructions.getTotalCount() == 0) {
                continue;
            }
            if (instructions.getCoveredCount() > 0) {
                covered.add(line);
            } else {
                missed.add(line);
            }
        }

        JSONObject result = new JSONObject();
        result.put("schema_version", 2);
        result.put("class_name", className);
        result.put("class_file", new File(classFile).getCanonicalPath());
        result.put("exec_file", new File(exec).getCanonicalPath());
        result.put("class_id_match", classIdMatch);
        result.put("scope_start_line", firstLine);
        result.put("scope_end_line", lastLine);
        result.put("scope_is_full_class", firstLine == selected.getFirstLine() && lastLine == selected.getLastLine());
        result.put("covered_lines", new JSONArray(covered));
        result.put("missed_lines", new JSONArray(missed));
        result.put("covered_line_count", covered.size());
        result.put("missed_line_count", missed.size());
        result.put("line_covered_ratio",
                covered.size() + missed.size() == 0 ? JSONObject.NULL
                        : ((double) covered.size()) / (covered.size() + missed.size()));
        result.put("jacoco_covered_line_count", covered.size());
        result.put("jacoco_missed_line_count", missed.size());
        result.put("covered_branch_count", coveredBranches);
        result.put("missed_branch_count", missedBranches);
        result.put("branch_covered_ratio",
                coveredBranches + missedBranches == 0 ? JSONObject.NULL
                        : ((double) coveredBranches) / (coveredBranches + missedBranches));
        result.put("covered_instruction_count", coveredInstructions);
        result.put("missed_instruction_count", missedInstructions);
        result.put("instruction_covered_ratio",
                coveredInstructions + missedInstructions == 0 ? JSONObject.NULL
                        : ((double) coveredInstructions) / (coveredInstructions + missedInstructions));

        File target = new File(output);
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileOutputStream stream = new FileOutputStream(target)) {
            stream.write((result.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String required(String[] args, String name) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (name.equals(args[index])) {
                return args[index + 1];
            }
        }
        throw new IllegalArgumentException("Missing argument " + name);
    }

    private static int optionalInt(String[] args, String name, int fallback) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (name.equals(args[index])) {
                return Integer.parseInt(args[index + 1]);
            }
        }
        return fallback;
    }
}
