package baselines.common.tools;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Baselines-owned focal-method context extractor. */
public final class TargetContextCli {
    private TargetContextCli() {
    }

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        ParseResult<CompilationUnit> result = new JavaParser().parse(parsed.source);
        if (!result.isSuccessful() || !result.getResult().isPresent()) {
            throw new IllegalArgumentException("Cannot parse Java source: "
                    + parsed.source + " problems=" + result.getProblems());
        }
        CompilationUnit unit = result.getResult().get();
        String simpleClassName = simpleName(parsed.className);
        ClassOrInterfaceDeclaration declaration = findClass(unit, simpleClassName);
        MethodDeclaration method = findMethod(declaration, parsed.methodSignature);

        JSONArray imports = new JSONArray();
        unit.getImports().forEach(value -> imports.put(value.toString().trim()));
        JSONArray fields = new JSONArray();
        JSONArray constructors = new JSONArray();
        JSONArray methodSignatures = new JSONArray();
        JSONArray publicMethodSignatures = new JSONArray();
        for (BodyDeclaration<?> member : declaration.getMembers()) {
            if (member instanceof FieldDeclaration) {
                fields.put(member.toString().trim());
            } else if (member instanceof ConstructorDeclaration) {
                constructors.put(member.toString().trim());
            } else if (member instanceof MethodDeclaration) {
                MethodDeclaration candidate = (MethodDeclaration) member;
                String signature = candidate.getDeclarationAsString(true, true, true);
                methodSignatures.put(signature);
                if (candidate.isPublic()) {
                    publicMethodSignatures.put(signature);
                }
            }
        }

        String packageName = unit.getPackageDeclaration().isPresent()
                ? unit.getPackageDeclaration().get().getNameAsString() : "";
        String classDeclaration = classDeclaration(declaration);
        JSONObject output = new JSONObject();
        output.put("schema_version", 1);
        output.put("source_path", parsed.source.toAbsolutePath().normalize().toString());
        output.put("class_name", parsed.className);
        output.put("simple_class_name", simpleClassName);
        output.put("method_signature", parsed.methodSignature);
        output.put("method_name", method.getNameAsString());
        output.put("package_name", packageName);
        output.put("imports", imports);
        output.put("class_declaration", classDeclaration);
        output.put("fields", fields);
        output.put("constructors", constructors);
        output.put("method_body", method.toString().trim());
        output.put("method_declaration", method.getDeclarationAsString(true, true, true));
        output.put("all_method_signatures", methodSignatures);
        output.put("public_method_signatures", publicMethodSignatures);
        output.put("Class_declaration", classDeclaration);
        output.put("Class_name", simpleClassName);
        output.put("Method_statement", parsed.methodSignature);
        output.put("Method_body", method.toString().trim());
        output.put("Filed", joinJsonArray(fields));
        output.put("constructors_text", joinJsonArray(constructors));
        output.put("all_method_signature", joinJsonArray(methodSignatures));
        output.put("contextMethod", "");

        Path parent = parsed.output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(parsed.output, output.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static ClassOrInterfaceDeclaration findClass(CompilationUnit unit, String name) {
        for (ClassOrInterfaceDeclaration candidate
                : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (candidate.getNameAsString().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Class not found in source: " + name);
    }

    private static String classDeclaration(ClassOrInterfaceDeclaration declaration) {
        ClassOrInterfaceDeclaration shell = declaration.clone();
        shell.getMembers().clear();
        String rendered = shell.toString().trim();
        int openingBrace = rendered.indexOf('{');
        return (openingBrace >= 0 ? rendered.substring(0, openingBrace).trim() : rendered) + " {";
    }

    private static MethodDeclaration findMethod(ClassOrInterfaceDeclaration declaration,
                                                String encodedSignature) {
        Signature expected = Signature.parse(encodedSignature);
        List<MethodDeclaration> nameAndArity = new ArrayList<MethodDeclaration>();
        for (MethodDeclaration candidate : declaration.getMethods()) {
            if (!candidate.getNameAsString().equals(expected.methodName)
                    || candidate.getParameters().size() != expected.parameterTypes.size()) {
                continue;
            }
            nameAndArity.add(candidate);
            if (parametersMatch(candidate, expected.parameterTypes)) {
                return candidate;
            }
        }
        if (nameAndArity.size() == 1) {
            return nameAndArity.get(0);
        }
        throw new IllegalArgumentException("Method not uniquely found: "
                + encodedSignature + " candidates=" + nameAndArity);
    }

    private static boolean parametersMatch(MethodDeclaration method, List<String> expectedTypes) {
        for (int i = 0; i < expectedTypes.size(); i++) {
            Parameter parameter = method.getParameter(i);
            String actual = parameter.getType().asString() + (parameter.isVarArgs() ? "[]" : "");
            if (!typeMatches(expectedTypes.get(i), actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean typeMatches(String expected, String actual) {
        String left = normalizeType(expected);
        String right = normalizeType(actual);
        return left.equals(right) || left.endsWith("." + right) || right.endsWith("." + left);
    }

    private static String normalizeType(String type) {
        return type.replace("...", "[]").replace(" ", "").replace("java.lang.", "");
    }

    private static String simpleName(String qualifiedName) {
        int index = qualifiedName.lastIndexOf('.');
        return index >= 0 ? qualifiedName.substring(index + 1) : qualifiedName;
    }

    private static String joinJsonArray(JSONArray values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(values.getString(i));
        }
        return out.toString();
    }

    private static final class Signature {
        private String methodName;
        private List<String> parameterTypes;

        private static Signature parse(String encoded) {
            int open = encoded.indexOf('(');
            int close = encoded.lastIndexOf(')');
            if (open < 0 || close < open) {
                throw new IllegalArgumentException("Invalid method signature: " + encoded);
            }
            String head = encoded.substring(0, open);
            int separator = head.indexOf('_');
            Signature signature = new Signature();
            signature.methodName = separator >= 0 ? head.substring(separator + 1) : head;
            signature.parameterTypes = splitTypes(encoded.substring(open + 1, close));
            return signature;
        }

        private static List<String> splitTypes(String text) {
            List<String> values = new ArrayList<String>();
            if (text.trim().isEmpty()) {
                return values;
            }
            int genericDepth = 0;
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                char current = text.charAt(i);
                if (current == '<') genericDepth++;
                else if (current == '>') genericDepth--;
                else if (current == ',' && genericDepth == 0) {
                    values.add(text.substring(start, i).trim());
                    start = i + 1;
                }
            }
            values.add(text.substring(start).trim());
            return values;
        }
    }

    private static final class Arguments {
        private Path source;
        private String className;
        private String methodSignature;
        private Path output;

        private static Arguments parse(String[] args) {
            Arguments out = new Arguments();
            for (int i = 0; args != null && i < args.length; i++) {
                String arg = args[i];
                if ("--source".equals(arg) && i + 1 < args.length) {
                    out.source = Paths.get(args[++i]).toAbsolutePath().normalize();
                } else if ("--class".equals(arg) && i + 1 < args.length) {
                    out.className = args[++i];
                } else if ("--method".equals(arg) && i + 1 < args.length) {
                    out.methodSignature = args[++i];
                } else if ("--output".equals(arg) && i + 1 < args.length) {
                    out.output = Paths.get(args[++i]).toAbsolutePath().normalize();
                } else {
                    throw new IllegalArgumentException("Unknown or incomplete argument: " + arg);
                }
            }
            if (out.source == null || out.className == null
                    || out.methodSignature == null || out.output == null) {
                throw new IllegalArgumentException("Usage: TargetContextCli --source <file> "
                        + "--class <fqn> --method <encoded-signature> --output <json>");
            }
            return out;
        }
    }
}
