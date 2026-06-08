

import com.github.gumtreediff.gen.jdt.JdtTreeGenerator;
// 若需显式注册解析器（通常不需要）可用 @Register(...) 注解
import com.github.gumtreediff.io.TreeIoUtils;
import com.github.gumtreediff.tree.ITree;
import com.github.gumtreediff.tree.TreeContext;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DumpAst_GumTree {
    public static void main(String[] args) throws Exception {
        String SRC_FILE_P = "src/main/java/demo/origin/ConstantPoolEntry.java"; // 原程序 p 源码

        // 1) 生成 AST
        TreeContext ctx = new JdtTreeGenerator().generateFromFile(new File(SRC_FILE_P));
        ITree root = ctx.getRoot();

        // 2) 打印 JSON（最直观）
        System.out.println(TreeIoUtils.toJson(ctx).toString());

        // 3) 额外：打印“缩进树”（含 pos/len 与行号）
        Path p = Path.of(SRC_FILE_P);
        String code = Files.readString(p);
        LineIndex li = new LineIndex(code);
        System.out.println("\n==== Pretty Tree ====");
        printTree(root, 0, li, ctx);
    }

static void printTree(ITree t, int depth, LineIndex li, TreeContext ctx) {
    String indent = "  ".repeat(depth);

    int pos = t.getPos();
    int len = t.getLength();
    int lineStart = li.lineOfOffset(pos);
    int lineEnd   = li.lineOfOffset(pos + Math.max(0, len - 1));

    String typeLabel = ctx.getTypeLabel(t);                 // 可读的节点类型名
    String label     = t.getLabel() == null ? "" : t.getLabel();

    System.out.printf("%s%s label='%s' [pos=%d len=%d] [lines=%d..%d]%n",
            indent, typeLabel, label, pos, len, lineStart, lineEnd);

    for (ITree c : t.getChildren()) {
        printTree(c, depth + 1, li, ctx);                  // 递归也是 ITree
    }
}

    /** 把字符偏移映射为行号（1-based） */
    static class LineIndex {
        private final int[] prefix; // 每行起始偏移
        LineIndex(String text) {
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') starts.add(i + 1);
            }
            this.prefix = starts.stream().mapToInt(Integer::intValue).toArray();
        }
        int lineOfOffset(int off) {
            if (off <= 0) return 1;
            int l = 0, r = prefix.length - 1, ans = 1;
            while (l <= r) {
                int m = (l + r) >>> 1;
                if (prefix[m] <= off) { ans = m + 1; l = m + 1; }
                else r = m - 1;
            }
            return Math.min(ans, prefix.length); // 1-based
        }
    }
}