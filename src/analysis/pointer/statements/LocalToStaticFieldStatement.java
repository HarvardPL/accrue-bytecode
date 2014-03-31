package analysis.pointer.statements;

import analysis.WalaAnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.LocalNode;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;

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
    private final LocalNode local;
    /**
     * assigned
     */
    private final LocalNode staticField;

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
    public LocalToStaticFieldStatement(LocalNode staticField, LocalNode local, IR ir,
            SSAPutInstruction i) {
        super(ir, i);
        assert !local.isStatic() : local + " is static";
        assert staticField.isStatic() : staticField + " is not static";
        this.local = local;
        this.staticField = staticField;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(haf.initialContext(), staticField);
        PointsToGraphNode r = new ReferenceVariableReplica(context, local);

        boolean changed = false;
        if (WalaAnalysisUtil.INCLUDE_IMPLICIT_ERRORS) {
            // During resolution of the symbolic reference to the class or
            // interface field, any of the exceptions pertaining to field
            // resolution (5.4.3.2) can be thrown.

            // Otherwise, if the resolved field is not a static (class) field or
            // an interface field, putstatic throws an
            // IncompatibleClassChangeError.

            // Otherwise, if the field is final, it must be declared in the
            // current class, and the instruction must occur in the <clinit>
            // method of the current class. Otherwise, an IllegalAccessError is
            // thrown.

            // TODO handle implicit errors for static put
        }
        
        return changed || g.addEdges(l, g.getPointsToSetFiltered(r, local.getExpectedType()));
    }

    @Override
    public String toString() {
        return local + " = " + staticField;
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
