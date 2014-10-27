package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import util.OrderedPair;
import analysis.pointer.analyses.HeapAbstractionFactory;
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
    private ReferenceVariable receiver;
    /**
     * Value assigned into field
     */
    private ReferenceVariable localVar;

    /**
     * Statement for an assignment into a field, o.f = v
     *
     * @param o
     *            points-to graph node for receiver of field access
     * @param f
     *            field assigned to
     * @param v
     *            points-to graph node for value assigned
     * @param m
     *            method the points-to statement came from
     */
    public LocalToFieldStatement(ReferenceVariable o, FieldReference f,
 ReferenceVariable v, ProgramPoint pp) {
        super(pp);
        this.field = f;
        this.receiver = o;
        this.localVar = v;
        assert !o.isFlowSensitive();
        assert !v.isFlowSensitive();
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf,
                              PointsToGraph g, GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode rec = new ReferenceVariableReplica(context, this.receiver, haf);
        PointsToGraphNode local = new ReferenceVariableReplica(context, this.localVar, haf);
        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());
        InterProgramPointReplica post = InterProgramPointReplica.create(context, this.programPoint().post());

        GraphDelta changed = new GraphDelta(g);

        if (delta == null) {
            // no delta, let's do some simple processing
            for (Iterator<InstanceKeyRecency> iter = g.pointsToIterator(rec, pre, originator); iter.hasNext();) {
                InstanceKeyRecency recHeapContext = iter.next();
                if (!g.isNullInstanceKey(recHeapContext)) {
                    ObjectField of = new ObjectField(recHeapContext, this.field);
                    // o.f can point to anything that local can.
                    GraphDelta d1 = g.copyEdges(local, pre, of, post, originator);
                    changed = changed.combine(d1);
                }
            }
        }
        else {
            // We check if o has changed what it points to. If it has, we need to make the new object fields
            // point to everything that the RHS can.
            for (Iterator<InstanceKeyRecency> iter = delta.pointsToIterator(rec, pre, originator); iter.hasNext();) {
                InstanceKeyRecency recHeapContext = iter.next();
                if (!g.isNullInstanceKey(recHeapContext)) {
                    ObjectField of = new ObjectField(recHeapContext, this.field);
                    GraphDelta d1 = g.copyEdges(local, pre, of, post, originator);
                    changed = changed.combine(d1);
                }
            }
        }

        return changed;
    }


    @Override
    public OrderedPair<Boolean, PointsToGraphNode> killsNode(Context context, PointsToGraph g) {
        ReferenceVariableReplica receiverReplica = new ReferenceVariableReplica(context, receiver, g.getHaf());
        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());
        Iterator<InstanceKeyRecency> iter = g.pointsToIterator(receiverReplica, pre, new StmtAndContext(this, context));

        if (!iter.hasNext()) {
            // the receiver currently point to nothing. Too early to tell if we kill a node
            return new OrderedPair<>(Boolean.FALSE, null);
        }
        // the receiver point to at least one object.
        InstanceKeyRecency pointedTo = iter.next();

        if (!iter.hasNext() && pointedTo.isRecent()) {
            // The receiver points to exactly one object, and it is the most recent.
            // So we will kill the field!
            // We definitely kill the ObjectField(pointedTo, field);
            return new OrderedPair<Boolean, PointsToGraphNode>(Boolean.TRUE, new ObjectField(pointedTo, field));
        }
        // the receiver either points to more than one object, or points to a non-most recent object.
        // either way, we don't kill the field.
        return new OrderedPair<>(Boolean.TRUE, null);
    }

    @Override
    public String toString() {
        return this.receiver
                + "."
                + (this.field != null
                ? this.field.getName() : PointsToGraph.ARRAY_CONTENTS)
                + " = " + this.localVar;
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0 || useNumber == 1;
        if (useNumber == 0) {
            this.receiver = newVariable;
            return;
        }
        this.localVar = newVariable;
    }

    @Override
    public List<ReferenceVariable> getUses() {
        List<ReferenceVariable> uses = new ArrayList<>(2);
        uses.add(this.receiver);
        uses.add(this.localVar);
        return uses;
    }

    @Override
    public ReferenceVariable getDef() {
        return null;
    }

    @Override
    public ReferenceVariableReplica getReadDependencyForKillField(Context ctxt, HeapAbstractionFactory haf) {
        return new ReferenceVariableReplica(ctxt, this.receiver, haf);
    }

    @Override
    public FieldReference getMaybeKilledField() {
        return this.getField();
    }

    /**
     * Get the field assigned into
     *
     * @return field assigned to
     */
    protected FieldReference getField() {
        return field;
    }

    @Override
    public boolean mayChangeFlowSensPointsToGraph() {
        // this depends on what the receiver points to. If it can point to a
        // recent object, then this will be a flow sensitive update.
        return true;
    }

}
