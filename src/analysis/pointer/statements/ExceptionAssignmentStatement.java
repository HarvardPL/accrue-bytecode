package analysis.pointer.statements;

import java.util.Set;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.statements.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;

public class ExceptionAssignmentStatement extends PointsToStatement {

    private final ReferenceVariable thrown;
    private final ReferenceVariable caught;
    private final Set<IClass> notType;

    /**
     * Statement for the assignment from a thrown exception to a caught
     * exception or the summary node for the exceptional exit to a method
     * 
     * @param thrown
     *            reference variable for the exception being thrown
     * @param caught
     *            reference variable for the caught exception (or summary for
     *            the method exit)
     * @param i
     *            instruction throwing the exception
     * @param ir
     *            code containing the instruction that throws the exception
     * @param notType
     *            types that the exception being caught cannot have since those
     *            types must have been caught by previous catch blocks
     */
    public ExceptionAssignmentStatement(ReferenceVariable thrown, ReferenceVariable caught, SSAInstruction i, IR ir,
                                    Set<IClass> notType) {
        super(ir, i);
        this.thrown = thrown;
        this.caught = caught;
        this.notType = notType;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, caught);
        PointsToGraphNode r = new ReferenceVariableReplica(context, thrown);

        if (DEBUG && g.getPointsToSetFiltered(r, caught.getExpectedType(), notType).isEmpty()
                                        && PointsToAnalysis.outputLevel >= 6) {
            System.err.println("GENERATED EXCEPTION: " + r + "\n\t"
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()) + " caught type: "
                                            + PrettyPrinter.typeString(caught.getExpectedType())
                                            + "\n\tAlready caught: " + notType);
        }

        return g.addEdges(l, g.getPointsToSetFiltered(r, caught.getExpectedType(), notType));
    }

    @Override
    public String toString() {
        return thrown + " = " + caught + "(" + PrettyPrinter.typeString(caught.getExpectedType()) + " NOT " + notType
                                        + ")";
    }
}
