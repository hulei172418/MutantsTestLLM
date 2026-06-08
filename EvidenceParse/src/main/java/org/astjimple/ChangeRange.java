package org.astjimple;

// Class representing AST changes
public class ChangeRange {
    final String kind; // Type such as Insert/Update/Delete/Move
    final boolean onSrcSide; // true means change applies to source (p), false means mutant (m)
    final int startLine, endLine; // Start and end line of the change
    final String nodeDesc; // Node description, typically a short string representation

    // Constructor
    ChangeRange(String kind, boolean onSrcSide, int startLine, int endLine, String nodeDesc) {
        this.kind = kind;
        this.onSrcSide = onSrcSide;
        this.startLine = startLine;
        this.endLine = endLine;
        this.nodeDesc = nodeDesc;
    }

    @Override
    public String toString() {
        return (onSrcSide ? "[p]" : "[m]") + kind + " " + startLine + "-" + endLine + " : " + nodeDesc;
    }
}
