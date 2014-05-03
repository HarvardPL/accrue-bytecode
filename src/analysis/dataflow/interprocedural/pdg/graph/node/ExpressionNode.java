package analysis.dataflow.interprocedural.pdg.graph.node;

import com.ibm.wala.ipa.callgraph.CGNode;

public class ExpressionNode extends PDGNode {

    private final CGNode n;

    /**
     * Create the node with the given type in the code and context given by the
     * call graph node, <code>n</code>.
     * 
     * @param human
     *            readable description of the node, may later be changed
     * 
     * @param type
     *            type of expression node being created
     * @param n
     *            call graph node containing the code and context the node is
     *            created in
     * @return PDG node of the given type created in the given call graph node
     */
    protected ExpressionNode(String description, PDGNodeType type, CGNode n) {
        super(description, type);
        this.n = n;
    }

    public CGNode getCGNode() {
        return n;
    }
}
