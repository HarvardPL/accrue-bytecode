package analysis.pointer.statements;

import java.util.Set;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.types.FieldReference;

/**
 * Points-to statement for an assignment into a field, o.f = v
 */
public class LocalToFieldStatement extends PointsToStatement {
    /**
     * Field assigned into
     */
    private final FieldReference field;
    /**
     * receiver for field access
     */
    private final ReferenceVariable receiver;
    /**
     * Value assigned into field
     */
    private final ReferenceVariable assigned;

    /**
     * Statement for an assignment into a field, o.f = v
     * 
     * @param f
     *            field assigned to
     * @param o
     *            points-to graph node for receiver of field access
     * @param v
     *            points-to graph node for value assigned
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    public LocalToFieldStatement(FieldReference f, ReferenceVariable o, ReferenceVariable v, IR ir, SSAPutInstruction i) {
        super(ir, i);
        this.field = f;
        this.receiver = o;
        this.assigned = v;
    }

    /**
     * Statement for an assignment into a the value field of a new string literal
     * 
     * @param f
     *            field assigned to
     * @param o
     *            points-to graph node for receiver of field access
     * @param v
     *            points-to graph node for value assigned
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    public LocalToFieldStatement(FieldReference f, ReferenceVariable o, ReferenceVariable v, IR ir, SSAInstruction i) {
        super(ir, i);
        this.field = f;
        this.receiver = o;
        this.assigned = v;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode rec = new ReferenceVariableReplica(context, receiver);
        PointsToGraphNode local = new ReferenceVariableReplica(context, assigned);
        Set<InstanceKey> localHeapContexts = g.getPointsToSetFiltered(local, field.getFieldType());

        if (DEBUG && localHeapContexts.isEmpty()) {
            System.err.println("LOCAL: " + local + " for "
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()) + " filtered on "
                                            + PrettyPrinter.typeString(field.getFieldType()) + " was "
                                            + PrettyPrinter.typeString(local.getExpectedType()));
            g.getPointsToSetFiltered(local, field.getFieldType());
        }
        if (DEBUG && g.getPointsToSet(rec).isEmpty()) {
            System.err.println("RECEIVER: " + rec + " for "
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }

        boolean changed = false;
        for (InstanceKey recHeapContext : g.getPointsToSet(rec)) {
            ObjectField f = new ObjectField(recHeapContext, field.getName().toString(), field.getFieldType());
            changed |= g.addEdges(f, localHeapContexts);
        }

        return changed;
    }

    @Override
    public String toString() {
        return receiver + "." + (field != null ? field.getName() : PointsToGraph.ARRAY_CONTENTS) + " = " + assigned;
    }
}
