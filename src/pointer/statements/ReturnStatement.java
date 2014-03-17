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
public class ReturnStatement implements PointsToStatement {

    /**
     * Node for return result
     */
    private final LocalNode result;
    /**
     * Node summarizing all return values for the method
     */
    private final LocalNode returnSummary;
    /**
     * Code this statement occurs in
     */
    private final IR ir;
    
    /**
     * Create a points-to statement for a return instruction
     * 
     * @param result
     *            Node for return result
     * @param returnSummary
     *            Node summarizing all return values for the method
     */
    public ReturnStatement(LocalNode result, LocalNode returnSummary, IR ir) {
        this.result = result;
        this.returnSummary = returnSummary;
        this.ir = ir;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {

        ReferenceVariableReplica returnRes = new ReferenceVariableReplica(context, result);
        ReferenceVariableReplica summaryRes = new ReferenceVariableReplica(context, returnSummary);

        return g.addEdges(summaryRes, g.getPointsToSetFiltered(returnRes, summaryRes.getExpectedType()));
    }

    @Override
    public IR getCode() {
        return ir;
    }
    @Override
    public String toString() {
        return("return " + result);
    }
}
