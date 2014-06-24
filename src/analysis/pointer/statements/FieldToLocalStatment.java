package analysis.pointer.statements;

import java.util.Collections;
import java.util.List;
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
 * Points-to statement for an Access a field and assign the result to a local. l = o.f
 */
public class FieldToLocalStatment extends PointsToStatement {

    /**
     * Field being accessed
     */
    private final FieldReference declaredField;
    /**
     * receiver of field access
     */
    private ReferenceVariable receiver;
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
     *            method the statement was created for
     */
    protected FieldToLocalStatment(ReferenceVariable l, ReferenceVariable o, FieldReference f, IMethod m) {
        super(m);
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

        Set<InstanceKey> s = g.getPointsToSet(rec);
        assert checkForNonEmpty(s, rec, "FIELD RECEIVER: " + this);

        boolean changed = false;
        for (InstanceKey recHeapContext : s) {
            ObjectField f = new ObjectField(recHeapContext, declaredField.getName().toString(),
                                            declaredField.getFieldType());

            Set<InstanceKey> fieldHCs = g.getPointsToSetFiltered(f, left.getExpectedType());
            assert checkForNonEmpty(fieldHCs, f, "FIELD filtered: " + PrettyPrinter.typeString(left.getExpectedType()));

            changed |= g.addEdges(left, fieldHCs);
        }

        return changed;
    }

    @Override
    public ReferenceVariable getDef() {
        return assignee;
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.singletonList(receiver);
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0;
        receiver = newVariable;
    }
}
