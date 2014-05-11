package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.ReferenceVariable;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAReturnInstruction;

/**
 * Points-to statement for a "return" instruction
 */
public class ReturnStatement extends PointsToStatement {

    /**
     * Node for return result
     */
    private final ReferenceVariable result;
    /**
     * Node summarizing all return values for the method
     */
    private final ReferenceVariable returnSummary;

    /**
     * Create a points-to statement for a return instruction
     * 
     * @param result
     *            Node for return result
     * @param returnSummary
     *            Node summarizing all return values for the method
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    public ReturnStatement(ReferenceVariable result, ReferenceVariable returnSummary, IR ir, SSAReturnInstruction i) {
        super(ir, i);
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
        return ("return " + result);
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
