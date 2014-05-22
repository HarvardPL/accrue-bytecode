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
import com.ibm.wala.ssa.SSAGetInstruction;

/**
 * Points-to statement for an assignment from a static field to a local
 * variable, v = o.x
 */
public class StaticFieldToLocalStatement extends PointsToStatement {

    /**
     * assignee
     */
    private final ReferenceVariable staticField;
    /**
     * assigned
     */
    private final ReferenceVariable local;

    /**
     * Statement for an assignment from a static field to a local, local =
     * ClassName.staticField
     * 
     * @param local
     *            points-to graph node for the assigned value
     * @param staticField
     *            points-to graph node for assignee
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    protected StaticFieldToLocalStatement(ReferenceVariable local, ReferenceVariable staticField, IR ir,
                                    SSAGetInstruction i) {
        super(ir, i);
        assert staticField.isSingleton() : staticField + " is not static";
        assert !local.isSingleton() : local + " is static";
        this.staticField = staticField;
        this.local = local;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {

        PointsToGraphNode l = new ReferenceVariableReplica(context, local);
        PointsToGraphNode r = new ReferenceVariableReplica(haf.initialContext(), staticField);

        if (DEBUG && g.getPointsToSet(r).isEmpty()) {
            System.err.println("STATIC FIELD: " + r + "\n\t"
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }

        return g.addEdges(l, g.getPointsToSet(r));
    }

    @Override
    public String toString() {
        return local + " = " + staticField;
    }
}
