package analysis.pointer.statements;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import util.OrderedPair;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.FieldReference;

/**
 * Points-to statement for an Access a field and assign the result to a local. l = o.f
 */
public class FieldToLocalStatement extends PointsToStatement {

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
    protected FieldToLocalStatement(ReferenceVariable l, ReferenceVariable o,
 FieldReference f, ProgramPoint pp) {
        super(pp);
        this.declaredField = f;
        this.receiver = o;
        this.assignee = l;
        assert !assignee.isFlowSensitive();
    }

    @Override
    public String toString() {
        return this.assignee + " = " + this.receiver + "." + this.declaredField.getName();
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf,
                              PointsToGraph g, GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode left = new ReferenceVariableReplica(context, this.assignee, haf);
        PointsToGraphNode rec = new ReferenceVariableReplica(context, this.receiver, haf);

        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());
        InterProgramPointReplica post = InterProgramPointReplica.create(context, this.programPoint().post());

        GraphDelta changed = new GraphDelta(g);

        if (delta == null) {
            // let's do the normal processing
            for (Iterator<InstanceKeyRecency> iter = g.pointsToIterator(rec, pre, originator); iter.hasNext();) {
                InstanceKeyRecency recHeapContext = iter.next();
                if (!g.isNullInstanceKey(recHeapContext)) {
                    ObjectField f = new ObjectField(recHeapContext, this.declaredField);
                    //GraphDelta d1 = g.copyFilteredEdges(f, filter, left);
                    GraphDelta d1 = g.copyEdges(f, pre, left, post);
                    changed = changed.combine(d1);
                }
                else {
                    // we ignore it if the receiver points to null.
                }
            }
        }
        else {
            // we have a delta. Let's be smart about how we use it.
            // Statement is v = o.f. First check if o points to anything new. If it does now point to some new abstract
            // object k, add everything that k.f points to to v's set.
            for (Iterator<InstanceKeyRecency> iter = delta.pointsToIterator(rec, pre, originator); iter.hasNext();) {
                InstanceKeyRecency recHeapContext = iter.next();
                if (!g.isNullInstanceKey(recHeapContext)) {
                    ObjectField f = new ObjectField(recHeapContext, this.declaredField);
                    GraphDelta d1 = g.copyEdges(f, pre, left, post);
                    changed = changed.combine(d1);
                }
                else {
                    // we ignore it if the receiver points to null.
                }

            }

            // Note: we do not need to check if there are any k.f's that have changed, since that will be
            // taken care of automatically by subset relations.
        }
        return changed;
    }

    @Override
    public OrderedPair<Boolean, PointsToGraphNode> killsNode(Context context, PointsToGraph g) {
        return new OrderedPair<Boolean, PointsToGraphNode>(Boolean.TRUE, assignee.isFlowSensitive()
                ? new ReferenceVariableReplica(context, assignee, g.getHaf()) : null);
    }

    @Override
    public List<ReferenceVariable> getDefs() {
        return Collections.singletonList(assignee);
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.singletonList(this.receiver);
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0;
        this.receiver = newVariable;
    }

    /**
     * Get the field being accessed
     *
     * @return accessed field
     */
    public FieldReference getField() {
        return this.declaredField;
    }

    @Override
    public boolean mayChangeFlowSensPointsToGraph() {
        assert !assignee.isFlowSensitive();
        // assignee is not flow sensitive.
        // XXX return false;
        return true;
    }

}
