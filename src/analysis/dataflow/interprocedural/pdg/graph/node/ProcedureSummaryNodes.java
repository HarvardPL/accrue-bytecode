package analysis.dataflow.interprocedural.pdg.graph.node;

import java.util.LinkedList;
import java.util.List;

import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.pdg.PDGContext;

import com.ibm.wala.ipa.callgraph.CGNode;

/**
 * Summary nodes used when connecting intra-procedural PDGs to form an
 * inter-procedural PDG.
 */
public class ProcedureSummaryNodes {

    /**
     * Formal summary nodes
     */
    private final List<PDGNode> formals;
    /**
     * Context before entering the method
     */
    private final PDGContext entry;
    /**
     * context after normal termination
     */
    private final PDGContext normalExit;
    /**
     * context after exceptional termination
     */
    private final PDGContext exExit;

    /**
     * Create the summary nodes for the method and context for the given call
     * graph node
     * 
     * @param n
     *            call graph node
     */
    public ProcedureSummaryNodes(CGNode n) {
        formals = new LinkedList<>();
        for (int j = 0; j < n.getMethod().getNumberOfParameters(); j++) {
            formals.add(PDGNodeFactory.findOrCreateOther("formal-" + j, PDGNodeType.FORMAL_SUMMARY, n, j));
        }
        entry = new PDGContext(null, null, PDGNodeFactory.findOrCreateOther("ENTRY PC", PDGNodeType.ENTRY_PC_SUMMARY,
                                        n, "ENTRY SUMMARY"));

        PDGNode ret = PDGNodeFactory.findOrCreateOther("NORMAL EXIT", PDGNodeType.EXIT_SUMMARY, n, ExitType.NORMAL);
        normalExit = new PDGContext(ret, null, PDGNodeFactory.findOrCreateOther("NORMAL EXIT PC",
                                        PDGNodeType.EXIT_PC_SUMMARY, n, ExitType.NORMAL));

        PDGNode ex = PDGNodeFactory.findOrCreateOther("EX EXIT", PDGNodeType.EXIT_SUMMARY, n, ExitType.NORMAL);
        exExit = new PDGContext(null, ex, PDGNodeFactory.findOrCreateOther("EX EXIT PC", PDGNodeType.EXIT_PC_SUMMARY,
                                        n, ExitType.EXCEPTIONAL));
    }

    /**
     * Get the PDG node for the jth formal
     * 
     * @param j
     *            formal number
     * @return jth formal summary node
     */
    public PDGNode getFormal(int j) {
        assert j < formals.size();
        return formals.get(j);
    }

    /**
     * Get the PDG nodes for the formal arguments
     * 
     * @return formal summary nodes
     */
    public List<PDGNode> getFormals() {
        return formals;
    }

    /**
     * Get the context for the program point just before the entry
     * 
     * @return the context before entering the method
     */
    public PDGContext getEntryContext() {
        return entry;
    }

    /**
     * Get the context for the program point just after normal termination
     * 
     * @return the context after normal exit
     */
    public PDGContext getNormalExitContext() {
        return normalExit;
    }

    /**
     * Get the context for the program point just after exceptional termination
     * 
     * @return the context after exceptional exit
     */
    public PDGContext getExceptionalExitContext() {
        return exExit;
    }
}
