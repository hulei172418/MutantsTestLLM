/**
 * Copyright (C) 2015  the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mujava.cmd;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Vector;
import java.util.logging.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mujava.*;
import mujava.util.Debug;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import static mujava.util.LocalJarFinder.*;

/**
 * <p>
 * Description:
 * </p>
 *
 * @author Jeff Offutt and Yu-Seung Ma
 * @version 1.0
 */
public class MutantsGenerator {
    private static final Logger logger = Logger.getLogger(MutantsGenerator.class.getName());

    static public void generateMutants(String[] file_names) {
        generateMutants(file_names, MutationSystem.cm_operators, MutationSystem.tm_operators);
    }

    static public void generateClassMutants(String[] file_names) {
        generateMutants(file_names, MutationSystem.cm_operators, null);
    }

    static public void generateClassMutants(String[] file_names, String[] class_ops) {
        generateMutants(file_names, class_ops, null);
    }

    static public void generateTraditionalMutants(String[] file_names) {
        generateMutants(file_names, null, MutationSystem.tm_operators);
    }

    static public void generateTraditionalMutants(String[] file_names, String[] traditional_ops) {
        generateMutants(file_names, null, traditional_ops);
    }

    static public void generateMutants(String[] file_names, String[] class_ops, String[] traditional_ops) {

        // file_names = Relative path from MutationSystem.SRC_PATH
        String file_name;
        for (int i = 0; i < file_names.length; i++) {
            file_name = file_names[i];
            System.out.println(file_name);
            try {
                System.out.println(i + " : " + file_name);
                String temp = file_name.substring(0, file_name.length() - ".java".length());
                String class_name = "";
                for (int ii = 0; ii < temp.length(); ii++) {
                    if ((temp.charAt(ii) == '\\') || (temp.charAt(ii) == '/')) {
                        class_name = class_name + ".";
                    } else {
                        class_name = class_name + temp.charAt(ii);
                    }
                }
                // Class c = Class.forName(class_name);

                int class_type = MutationSystem.getClassType(class_name);
                if (class_type == MutationSystem.NORMAL) {
                } else if (class_type == MutationSystem.MAIN) {
                    System.out.println(" -- " + file_name + " class contains 'static void main()' method.");
                    System.out.println("    Pleas note that mutants are not generated for the 'static void main()' method");
                } else {
                    switch (class_type) {
                        case MutationSystem.MAIN:
                            System.out.println(" -- Can't apply because " + file_name + " contains only 'static void main()' method.");
                            break;
                        case MutationSystem.INTERFACE:
                            System.out.println(" -- Can't apply because " + file_name + " is 'interface' ");
                            break;
                        case MutationSystem.ABSTRACT:
                            System.out.println(" -- Can't apply because " + file_name + " is 'abstract' class ");
                            break;
                        case MutationSystem.APPLET:
                            System.out.println(" -- Can't apply because " + file_name + " is 'applet' class ");
                            break;
                        case MutationSystem.GUI:
                            System.out.println(" -- Can't apply because " + file_name + " is 'GUI' class ");
                            break;
                    }
                    deleteDirectory();
                    continue;
                }

                // [2] Apply mutation testing
                setMutationSystemPathFor(file_name);

                //File[] original_files = new File[1];
                //original_files[0] = new File(MutationSystem.SRC_PATH,file_name);
                File original_file = new File(MutationSystem.SRC_PATH, file_name);

                AllMutantsGenerator genEngine;
                Debug.setDebugLevel(3);
                genEngine = new AllMutantsGenerator(original_file, class_ops, traditional_ops);
                genEngine.makeMutants();
                genEngine.compileMutants();
            } catch (OpenJavaException oje) {
                System.out.println("[OJException] " + file_name + " " + oje.toString());
                //System.out.println("Can't generate mutants for " +file_name + " because OpenJava " + oje.getMessage());
                deleteDirectory();
            } catch (Exception exp) {
                System.out.println("[Exception] " + file_name + " " + exp.toString());
                exp.printStackTrace();
                //System.out.println("Can't generate mutants for " +file_name + " due to exception" + exp.getClass().getName());
                //exp.printStackTrace();
                deleteDirectory();
            } catch (Error er) {
                System.out.println("[Error] " + file_names + " " + er.toString());
                //System.out.println("Can't generate mutants for " +file_name + " due to error" + er.getClass().getName());
                deleteDirectory();
            }
        }
    }

    static void setMutationSystemPathFor(String file_name) {
        try {
            String temp;
            temp = file_name.substring(0, file_name.length() - ".java".length());
            temp = temp.replace('/', '.');
            temp = temp.replace('\\', '.');
            int separator_index = temp.lastIndexOf(".");
            if (separator_index >= 0) {
                MutationSystem.CLASS_NAME = temp.substring(separator_index + 1, temp.length());
            } else {
                MutationSystem.CLASS_NAME = temp;
            }

            String mutant_dir_path = MutationSystem.MUTANT_HOME + "/" + temp;
            File mutant_path = new File(mutant_dir_path);
            if (!mutant_path.exists()) {
                boolean created = mutant_path.mkdirs();
                if (!created) {
                    System.err.println("Failed to create directory: " + mutant_path.getAbsolutePath());
                }
            }

            String class_mutant_dir_path = mutant_dir_path + "/" + MutationSystem.CM_DIR_NAME;
            File class_mutant_path = new File(class_mutant_dir_path);
            if (!class_mutant_path.exists()) {
                boolean created = class_mutant_path.mkdirs();
                if (!created) {
                    System.err.println("Failed to create directory: " + class_mutant_path.getAbsolutePath());
                }
            }

            String traditional_mutant_dir_path = mutant_dir_path + "/" + MutationSystem.TM_DIR_NAME;
            File traditional_mutant_path = new File(traditional_mutant_dir_path);
            if (!traditional_mutant_path.exists()) {
                boolean created = traditional_mutant_path.mkdirs();
                if (!created) {
                    System.err.println("Failed to create directory: " + traditional_mutant_path.getAbsolutePath());
                }
            }

            String original_dir_path = mutant_dir_path + "/" + MutationSystem.ORIGINAL_DIR_NAME;
            File original_path = new File(original_dir_path);
            if (!original_path.exists()) {
                boolean created = original_path.mkdirs();
                if (!created) {
                    System.err.println("Failed to create directory: " + original_path.getAbsolutePath());
                }
            }

            MutationSystem.CLASS_MUTANT_PATH = class_mutant_dir_path;
            MutationSystem.TRADITIONAL_MUTANT_PATH = traditional_mutant_dir_path;
            MutationSystem.ORIGINAL_PATH = original_dir_path;
            MutationSystem.DIR_NAME = temp;

        } catch (Exception e) {
            System.err.println(e);
        }
    }

    static void deleteDirectory() {
        File originalDir = new File(MutationSystem.MUTANT_HOME + "/" + MutationSystem.DIR_NAME
                + "/" + MutationSystem.ORIGINAL_DIR_NAME);
        while (originalDir.delete()) {
        }

        File cmDir = new File(MutationSystem.MUTANT_HOME + "/" + MutationSystem.DIR_NAME
                + "/" + MutationSystem.CM_DIR_NAME);
        while (cmDir.delete()) {
        }

        File tmDir = new File(MutationSystem.MUTANT_HOME + "/" + MutationSystem.DIR_NAME
                + "/" + MutationSystem.TM_DIR_NAME);
        while (tmDir.delete()) {
        }

        File myHomeDir = new File(MutationSystem.MUTANT_HOME + "/" + MutationSystem.DIR_NAME);
        while (myHomeDir.delete()) {
        }
    }

    static void run(String file_path){
        // String home_path = System.getProperty("user.dir") + "/../"+"Programs/"+file_path+"/"+file_path;
        // String home_path = System.getProperty("user.dir") + "/../"+"Programs/"+"commons-math-4.0-beta1/"+file_path;  // commons-math-4.0-beta1专属
        String home_path = System.getProperty("user.dir") + "/../"+"Programs/"+file_path;
        MutationSystem.setJMutationStructure(home_path);
        Vector file_names = MutationSystem.getNewTragetFiles();
        for (int i=0;i< file_names.size();i++){
            String file_name = (String) file_names.get(i);

            // file_name = "test/java/org/joda/time/MockZone.java";
            // 统一路径分隔符为正斜杠
            file_name = file_name.replace("\\", "/");

            String regex = "(^|/|\\.\\.|\\./)test(/|$|\\.)"; // 过滤掉 test 目录下的.java文件
            Pattern pattern = Pattern.compile(regex);
            // 如果路径中包含 "/test/"，则过滤掉
            Matcher matcher = pattern.matcher(file_name);
            if (matcher.find()){
                continue;
            }

            try {
                setMutationSystemPathFor(file_name);

                MutationSystem.JAR_PATH = buildProjectClasspath(MutationSystem.SYSTEM_HOME);

                File original_file = new File(MutationSystem.SRC_PATH, file_name);
                AllMutantsGenerator genEngine;
                Debug.setDebugLevel(3);
                genEngine = new AllMutantsGenerator(original_file, null, MutationSystem.tm_operators);
                genEngine.makeMutants();
                genEngine.compileMutants();
                // moveSrcClass(MutationSystem.SRC_PATH+"/"+file_name, MutationSystem.ORIGINAL_PATH);
            } catch (OpenJavaException oje) {
                System.out.println("[OJException] " + file_name + " " + oje.toString());
                logger.log(Level.SEVERE, "[OJException] " + file_name + "####" + oje.toString());
                //System.out.println("Can't generate mutants for " +file_name + " because OpenJava " + oje.getMessage());
                deleteDirectory();
            } catch (Exception exp) {
                System.out.println("[Exception] " + file_name + " " + exp.toString());
                logger.log(Level.SEVERE, "[Exception] " + file_name + "####" + exp.toString());
                exp.printStackTrace();
                //System.out.println("Can't generate mutants for " +file_name + " due to exception" + exp.getClass().getName());
                //exp.printStackTrace();
                deleteDirectory();
            } catch (Error er) {
                System.out.println("[Error] " + file_names + " " + er.toString());
                logger.log(Level.SEVERE, "[Error] " + file_name + "####" + er.toString());
                //System.out.println("Can't generate mutants for " +file_name + " due to error" + er.getClass().getName());
                deleteDirectory();
            }
        }
    }

    private static void setupLogger(String logFile) throws IOException {
        Files.createDirectories(Paths.get("logs"));

        FileHandler fileHandler = new FileHandler(logFile, false);
        fileHandler.setFormatter(new SimpleFormatterWithoutPrefix());

        Logger rootLogger = Logger.getLogger("");
        rootLogger.addHandler(fileHandler);
        rootLogger.setLevel(Level.SEVERE);

        // 移除默认的控制台输出
        Handler[] handlers = rootLogger.getHandlers();
        for (Handler handler : handlers) {
            if (handler instanceof java.util.logging.ConsoleHandler) {
                rootLogger.removeHandler(handler);
            }
        }

        logger.info("Logger initialized.");
    }

    private static class SimpleFormatterWithoutPrefix extends Formatter {
        @Override
        public String format(LogRecord record) {
            return record.getLevel() + ": " + record.getMessage() + "\n";
        }
    }

    public static void main(String[] args) {
        String s = "";
        String parentDir = "";
        String artId = "";
        try {
            String config = MutationSystem.SYSTEM_HOME + "/mujava.config";
            String jsonResult = getFileDir(config, "MAVEN_REPOSITORY_HOME");
            MutationSystem.MAVEN_HOME = getMaven(config, "MAVEN_HOME");
            assert jsonResult != null;

            String logFile = "logs/statistic.log";
            setupLogger(logFile);

            JSONArray jsonArray = new JSONArray(jsonResult);
            for (int i = 3; i < 4; i++) {
            // for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject dependency = jsonArray.getJSONObject(i);
                String groupId = dependency.getString("groupId");
                String artifactId = dependency.getString("artifactId");
                String version = dependency.getString("version");
                String jarPath = dependency.optString("jarPath", "[未找到]");

                if(artifactId.contains("commons-math4")){
                    parentDir = "commons-math"+"-"+version;
                    artId = artifactId.replace("math4", "math");
                }else if (artifactId.contains("commons-numbers")){
                    parentDir = "commons-numbers"+"-"+version;
                    artId = artifactId;
                }
                else {
                    parentDir = artifactId+"-"+version;
                    artId = "";
                }
                if ("".equals(artId)) {
                    s = parentDir+"/"+parentDir;
                }
                else{
                    s = parentDir+"/"+parentDir+"/"+artId;
                }

                // s = "oot/oot";
                // jarPath = "E:/PHD/testJava/testJavaMu/target/testJava-1.0.0.jar";
                //
                MutationSystem.JAR_PATH = jarPath;
                // String home_path = System.getProperty("user.dir") + "/../"+"Programs/"+s;
                // System.out.println(home_path);
                run(s);
            }
        }catch (Exception e){
            System.out.println("[Error] " + s + e.toString());
        }
    }
}