package analysis.pointer.statements;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;

/**
 * Points-to statement for a local assignment, left = right
 */
public class LocalToLocalStatement extends PointsToStatement {

    /**
     * assignee
     */
    private final ReferenceVariable left;
    /**
     * assigned
     */
    private final ReferenceVariable right;

    /**
     * Statement for a local assignment, left = right
     * 
     * @param left
     *            points-to graph node for assignee
     * @param right
     *            points-to graph node for the assigned value
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    protected LocalToLocalStatement(ReferenceVariable left, ReferenceVariable right, IR ir, SSAInstruction i) {
        super(ir, i);
        assert !left.isSingleton() : left + " is static";
        assert !right.isSingleton() : right + " is static";
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, left);
        PointsToGraphNode r = new ReferenceVariableReplica(context, right);
        if (DEBUG && g.getPointsToSetFiltered(r, left.getExpectedType()).isEmpty()) {
            System.err.println("LOCAL: " + r + " for " + PrettyPrinter.instructionString(getInstruction(), getCode())
                                            + " in " + PrettyPrinter.methodString(getCode().getMethod())
                                            + " filtered on " + PrettyPrinter.typeString(left.getExpectedType())
                                            + " was " + PrettyPrinter.typeString(r.getExpectedType()));
        }
        return g.addEdges(l, g.getPointsToSetFiltered(r, left.getExpectedType()));
    }

    @Override
    public String toString() {
        return left + " = " + right;
    }
}
