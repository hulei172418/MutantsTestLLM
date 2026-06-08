package org.utils;

import java.io.*;
import java.util.*;

public class DotToImageConverter {

    public static void main(String[] args) {
        // For testing: pass current directory
        // convertDotFilesInDirectory(new File("."));
    }

    public static void convertDotFilesInDirectory(File dir) {
        if (!dir.isDirectory()) {
            System.err.println("❌ The input path is not a valid directory: " + dir.getAbsolutePath());
            return;
        }

        // Step 1: Check if dot is available
        if (!isDotAvailable()) {
            System.err.println("❌ Graphviz not installed or 'dot' not in PATH!");
            return;
        }

        // Step 2: Locate .dot files
        File[] dotFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".dot"));
        if (dotFiles == null || dotFiles.length == 0) {
            System.out.println("ℹ️ No .dot files found in directory: " + dir.getAbsolutePath());
            return;
        }

        // Step 3: Execute conversion
        for (File dotFile : dotFiles) {
            String inputName = dotFile.getName();
            String outputName = inputName.replaceAll("\\.dot$", ".jpg");

            List<String> command = Arrays.asList(
                    "dot", "-Tjpg",
                    dotFile.getAbsolutePath(),
                    "-o",
                    new File(dir, outputName).getAbsolutePath());

            // System.out.println("▶ Executing command: " + String.join(" ", command));

            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                // Optional: print dot execution output
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    // System.out.println("✅ Successfully generated: " + outputName);
                } else {
                    System.err.println("❌Conversion failed: " + inputName);
                }

            } catch (IOException | InterruptedException e) {
                System.err.println("❌ Execution error: " + e.getMessage());
            }
        }
    }

    private static boolean isDotAvailable() {
        try {
            Process process = new ProcessBuilder("dot", "-V").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String output = reader.readLine();
            process.waitFor();
            return output != null && output.contains("dot - graphviz");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
