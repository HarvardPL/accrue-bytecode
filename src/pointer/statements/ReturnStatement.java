package pointer.statements;

import pointer.LocalNode;
import pointer.PointsToGraph;
import pointer.ReferenceVariableReplica;
import pointer.analyses.HeapAbstractionFactory;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

/**
 * Points to statement for a "return" instruction
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
     * Create a points to statement for a return instruction
     * 
     * @param result
     *            Node for return result
     * @param returnSummary
     *            Node summarizing all return values for the method
     */
    public ReturnStatement(LocalNode result, LocalNode returnSummary) {
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
    public TypeReference getExpectedType() {
        return result.getExpectedType();
    }

}
