package mujava;


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class Test {
    public static void main(String[] args) throws IOException {
        String f_name="E:/PHD/muJava-idea/testset/result/Calculate/traditional_mutants/int_add(int,int)/AORB_1/Calculate.java";
        //1.将目标文件并封装为对象
        File outfile = new File(f_name);
        String parent_dir = outfile.getParent();
        File dir_f = new File(parent_dir);
        dir_f.mkdirs();

        //2.创建FileWriter类，并传入目标文件对象
        //如果new.txt不存在，会自动创建，如果存在，则会覆盖原内容
        FileWriter fw = new FileWriter(outfile);
        /*//file后的append参数true表示追加，false表示覆盖
        FileWriter fw = new FileWriter(file,true);  //添加的内容会追加到file后面
        FileWriter fw = new FileWriter(file,false);   //添加的内容会覆盖到file的内容*/

        //3.将数据输出到目标文件：write()方法
        String s="abchello老师你好";
        //1）一个一个字符输入到文件
        for (int i = 0; i < s.length(); i++) {
            //System.out.println(s.charAt(i));//可以返回字符串中的每一个字符
            fw.write(s.charAt(i));
        }
        fw.close();
    }
}
