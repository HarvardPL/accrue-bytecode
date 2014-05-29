package analysis.pointer.statements;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

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
    protected ReturnStatement(ReferenceVariable result, IMethod m) {
        super(ir, i);
        this.result = result;
        this.returnSummary = returnSummary;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        ReferenceVariableReplica returnRes = new ReferenceVariableReplica(context, result);
        ReferenceVariableReplica summaryRes = new ReferenceVariableReplica(context, returnSummary);

        if (DEBUG && g.getPointsToSet(returnRes).isEmpty()) {
            System.err.println("RETURN RES: " + returnRes + " for "
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }

        return g.addEdges(summaryRes, g.getPointsToSet(returnRes));
    }

    @Override
    public String toString() {
        return ("return " + result);
    }
}
