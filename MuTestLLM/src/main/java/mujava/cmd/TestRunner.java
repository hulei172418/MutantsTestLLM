/**
 * Copyright (C) 2015  the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package mujava.cmd;

import mujava.MutationSystem;
import mujava.TestExecuter;
import mujava.test.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * <p>
 * Description: 
 * </p>
 *
 * @author Jeff Offutt and Yu-Seung Ma 
 * @version 1.0
 *
 */

public class TestRunner {
	static final boolean CLASS_MODE = true;
	static final boolean TRADITIONAL_MODE = false;

	static TestResult runTest(String targetClassName, String testSetName, int timeout_secs, boolean mode){

		if((targetClassName!=null)&&(testSetName!=null)){
			try{
				String home_path = "";
				MutationSystem.setJMutationStructure(home_path);
				TestExecuter test_engine = new TestExecuter(targetClassName);
				test_engine.setTimeOut(timeout_secs);

				// First, read (load) test suite class.
				test_engine.readTestSet(testSetName);

				TestResult test_result = new TestResult();
				if(mode==CLASS_MODE){
					test_result = test_engine.runClassMutants();
				}else{
					String methodSignature = "All method";
					test_engine.computeOriginalTestResults();
					test_result = test_engine.runTraditionalMutants(methodSignature.toString());
				}
				int killed_num = test_result.killed_mutants.size();
				int live_num = test_result.live_mutants.size();
				int total = killed_num + live_num;
				float mutant_score = (total == 0) ? 0.0f : (killed_num * 100.0f) / total;
				System.out.println(
						"\n\nkilled_mutants=" + test_result.killed_mutants +
								";\nlive_mutants=" + test_result.live_mutants +
								";\nmutant_score=" + mutant_score
				);
				return test_result;
			}catch(Exception e){
				System.err.println(e);
				return null;
			}
		}else{
			System.out.println(" [Error] Please check test target or test suite ");
			return null;
		}

	}
	static TestResult runClassTest(String targetClassName, String testSetName, int timeout_secs){
		return runTest(targetClassName,testSetName,timeout_secs,CLASS_MODE);
	}


	static TestResult runTraditionalTest(String targetClassName, String testSetName, int timeout_secs){
		return runTest(targetClassName,testSetName,timeout_secs,TRADITIONAL_MODE);
	}

	public static List<String> listTestClassNames(Path dir) throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.toString().endsWith(".class"))
					// 过滤内部类：Foo$1.class、Foo$Inner.class
					.filter(p -> !p.getFileName().toString().contains("$"))
					// 过滤 scaffolding：Foo_ESTest_scaffolding.class
					.filter(p -> !p.getFileName().toString().endsWith("_scaffolding.class"))
					.map(p -> p.getFileName().toString().replace(".class", ""))
					.sorted()
					.collect(Collectors.toList());
		}
	}

	public static void main(String[] args){
		Path dir = Paths.get("E:\\PHD\\testJava\\Programs\\commons-csv-1.2\\evoSuite\\classes\\org\\apache\\commons\\csv");
		try {
			List<String> clsNms = listTestClassNames(dir);
			// for (int i = 12; i < clsNms.size(); i++) {
			for (int i = 4; i < 5; i++) {
				String clsNm = clsNms.get(i);
				String targetClassName = "main.java.org.apache.commons.csv.CSVRecord";
				String testSetName = clsNm+"";
				int timeout_secs = 5 * 1000;
				TestResult res = runTraditionalTest(targetClassName, testSetName, timeout_secs);
			}
		}catch (IOException e){
			System.out.println("Input IOException");
		}
	}
}