package pointer.statements;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.LocalNode;
import pointer.graph.PointsToGraph;
import pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;

/**
 * Points-to statement for a "return" instruction
 */
public class ReturnStatement extends PointsToStatement {

    /**
     * Node for return result
     */
    private final LocalNode result;
    /**
     * Node summarizing all return values for the method
     */
    private final LocalNode returnSummary;
    
    /**
     * Create a points-to statement for a return instruction
     * 
     * @param result
     *            Node for return result
     * @param returnSummary
     *            Node summarizing all return values for the method
     */
    public ReturnStatement(LocalNode result, LocalNode returnSummary, IR ir) {
        super(ir);
        this.result = result;
        this.returnSummary = returnSummary;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        ReferenceVariableReplica returnRes = new ReferenceVariableReplica(context, result);
        ReferenceVariableReplica summaryRes = new ReferenceVariableReplica(context, returnSummary);

        return g.addEdges(summaryRes, g.getPointsToSetFiltered(returnRes, summaryRes.getExpectedType()));
    }

    @Override
    public String toString() {
        return("return " + result);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((this.result == null) ? 0 : this.result.hashCode());
        result = prime * result + ((returnSummary == null) ? 0 : returnSummary.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        ReturnStatement other = (ReturnStatement) obj;
        if (result == null) {
            if (other.result != null)
                return false;
        } else if (!result.equals(other.result))
            return false;
        if (returnSummary == null) {
            if (other.returnSummary != null)
                return false;
        } else if (!returnSummary.equals(other.returnSummary))
            return false;
        return true;
    }
}
