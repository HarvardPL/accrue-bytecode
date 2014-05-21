package analysis.pointer.statements;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAPutInstruction;

/**
 * Points-to statement for an assignment from a local into a static field
 */
public class LocalToStaticFieldStatement extends PointsToStatement {

    /**
     * assignee
     */
    private final ReferenceVariable local;
    /**
     * assigned
     */
    private final ReferenceVariable staticField;

    /**
     * Statement for an assignment from a local into a static field,
     * ClassName.staticField = local
     * 
     * @param staticField
     *            points-to graph node for the assigned value
     * @param local
     *            points-to graph node for assignee
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    public LocalToStaticFieldStatement(ReferenceVariable staticField, ReferenceVariable local, IR ir,
                                    SSAPutInstruction i) {
        super(ir, i);
        assert !local.isSingleton() : local + " is static";
        assert staticField.isSingleton() : staticField + " is not static";
        this.local = local;
        this.staticField = staticField;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(haf.initialContext(), staticField);
        PointsToGraphNode r = new ReferenceVariableReplica(context, local);

        if (DEBUG && g.getPointsToSet(r).isEmpty()) {
            System.err.println("LOCAL: " + local + " for "
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }
        return g.addEdges(l, g.getPointsToSet(r));
    }

    @Override
    public String toString() {
        return staticField + " = " + local;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((local == null) ? 0 : local.hashCode());
        result = prime * result + ((staticField == null) ? 0 : staticField.hashCode());
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
        LocalToStaticFieldStatement other = (LocalToStaticFieldStatement) obj;
        if (local == null) {
            if (other.local != null)
                return false;
        } else if (!local.equals(other.local))
            return false;
        if (staticField == null) {
            if (other.staticField != null)
                return false;
        } else if (!staticField.equals(other.staticField))
            return false;
        return true;
    }
}
