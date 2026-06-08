package mujava.testgenerator.tools;

import java.nio.file.Files;
import java.nio.file.Paths;

import static mujava.testgenerator.tools.CommonUtils.requireNonBlank;

/**
 * Validates generation requests before the pipeline starts.
 */
public final class RequestValidator {
    private RequestValidator() {
    }

    public static void validate(Request request) {
        requireNonBlank(request.sourceModuleHome, "sourceModuleHome");
        requireNonBlank(request.resultModuleHome, "resultModuleHome");
        requireNonBlank(request.targetClassName, "targetClassName");
        requireNonBlank(request.methodSignature, "methodSignature");
        requireNonBlank(request.mutantName, "mutantName");
        requireNonBlank(request.outputJsonPath, "outputJsonPath");

        if (!Files.isRegularFile(Paths.get(request.outputJsonPath))) {
            throw new IllegalArgumentException("outputJsonPath does not exist: " + request.outputJsonPath);
        }
        if (!Files.isDirectory(Paths.get(request.sourceModuleHome))) {
            throw new IllegalArgumentException("sourceModuleHome is not a directory: " + request.sourceModuleHome);
        }
        if (!Files.isDirectory(Paths.get(request.resultModuleHome))) {
            throw new IllegalArgumentException("resultModuleHome is not a directory: " + request.resultModuleHome);
        }
    }
}
