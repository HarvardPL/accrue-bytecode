package analysis.pointer.statements;

import java.util.LinkedHashSet;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;

/**
 * Points-to statement for an Access a field and assign the result to a local. l
 * = o.f
 */
public class FieldToLocalStatment extends PointsToStatement {

    /**
     * Field being accessed
     */
    private final FieldReference declaredField;
    /**
     * receiver of field access
     */
    private final ReferenceVariable receiver;
    /**
     * local assigned into
     */
    private final ReferenceVariable assignee;

    /**
     * Points-to statement for a field access assigned to a local, l = o.f
     * 
     * @param l
     *            points-to graph node for local assigned into
     * @param o
     *            points-to graph node for receiver of field access
     * @param f
     *            field accessed
     * @param m
     */
    protected FieldToLocalStatment(ReferenceVariable l, ReferenceVariable o, FieldReference f, IMethod m) {
        super(ir, i);
        this.declaredField = f;
        this.receiver = o;
        this.assignee = l;
    }

    @Override
    public String toString() {
        return assignee + " = " + receiver + "." + declaredField.getName();
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode left = new ReferenceVariableReplica(context, assignee);
        PointsToGraphNode rec = new ReferenceVariableReplica(context, receiver);
        if (DEBUG && g.getPointsToSet(rec).isEmpty()) {
            System.err.println("RECEIVER: " + rec + " for "
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }

        Set<InstanceKey> fields = new LinkedHashSet<>();
        for (InstanceKey recHeapContext : g.getPointsToSet(rec)) {
            ObjectField f = new ObjectField(recHeapContext, declaredField.getName().toString(),
                                            declaredField.getFieldType());
            if (DEBUG && g.getPointsToSetFiltered(f, left.getExpectedType()).isEmpty()) {
                System.err.println("FIELD: " + f + " for "
                                                + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                                + PrettyPrinter.methodString(getCode().getMethod()) + " filtered on "
                                                + PrettyPrinter.typeString(left.getExpectedType()));
            }
            for (InstanceKey fieldHeapContext : g.getPointsToSetFiltered(f, left.getExpectedType())) {
                fields.add(fieldHeapContext);
            }
        }

        return g.addEdges(left, fields);
    }
}
