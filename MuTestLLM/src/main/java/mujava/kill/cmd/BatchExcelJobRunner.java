package mujava.kill.cmd;

/** Separate batch entry, so Excel traversal is no longer the public face of the core runner. */
public final class BatchExcelJobRunner {

    private BatchExcelJobRunner() {}

    public static void runFromExcel(String excelPath, int timeoutMillis) {
        TestRunner_MultiProcess_batched.runBatchTraditionalFromExcel(excelPath, timeoutMillis);
    }

    public static void main(String[] args) {
        BatchExcelJobRunner.runFromExcel(
                "../MutantParse/data/mutant_statistic_total - 副本2.xlsx",
                1000
        );
    }
}
