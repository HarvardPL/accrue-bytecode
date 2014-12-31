package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
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
 * Points-to statement for an assignment into a field, o.f = v
 */
public class LocalToFieldStatement<IK extends InstanceKey, C extends Context> extends PointsToStatement<IK, C> {
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
     * @param o points-to graph node for receiver of field access
     * @param f field assigned to
     * @param v points-to graph node for value assigned
     * @param m method the points-to statement came from
     */
    public LocalToFieldStatement(ReferenceVariable o, FieldReference f, ReferenceVariable v, IMethod m) {
        super(m);
        this.field = f;
        this.receiver = o;
        this.localVar = v;
    }

    @Override
    public GraphDelta<IK, C> process(C context, HeapAbstractionFactory<IK, C> haf, PointsToGraph<IK, C> g,
                                     GraphDelta<IK, C> delta, StatementRegistrar<IK, C> registrar,
                                     StmtAndContext<IK, C> originator) {
        PointsToGraphNode rec = new ReferenceVariableReplica(context, this.receiver, haf);
        PointsToGraphNode local = new ReferenceVariableReplica(context, this.localVar, haf);

        GraphDelta<IK, C> changed = new GraphDelta<>(g);

        if (delta == null) {
            // no delta, let's do some simple processing
            for (Iterator<IK> iter = g.pointsToIterator(rec, originator); iter.hasNext();) {
                IK recHeapContext = iter.next();

                ObjectField f = new ObjectField(recHeapContext, this.field);
                // o.f can point to anything that local can.
                GraphDelta<IK, C> d1 = g.copyEdges(local, f);

                changed = changed.combine(d1);
            }
        }
        else {
            // We check if o has changed what it points to. If it has, we need to make the new object fields
            // point to everything that the RHS can.
            for (Iterator<IK> iter = delta.pointsToIterator(rec); iter.hasNext();) {
                IK recHeapContext = iter.next();
                ObjectField contents = new ObjectField(recHeapContext, this.field);
                GraphDelta<IK, C> d1 = g.copyEdges(local, contents);
                changed = changed.combine(d1);
            }
        }

        return changed;
    }

    @Override
    public String toString() {
        return this.receiver + "." + (this.field != null ? this.field.getName() : PointsToGraph.ARRAY_CONTENTS) + " = "
                + this.localVar;
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
    public Collection<?> getReadDependencies(C ctxt, HeapAbstractionFactory<IK, C> haf) {
        ReferenceVariableReplica rec = new ReferenceVariableReplica(ctxt, this.receiver, haf);
        ReferenceVariableReplica var = new ReferenceVariableReplica(ctxt, this.localVar, haf);
        List<Object> uses = new ArrayList<>(2);
        uses.add(rec);
        uses.add(var);

        return uses;

    }

    @Override
    public Collection<?> getWriteDependencies(C ctxt, HeapAbstractionFactory<IK, C> haf) {
        return Collections.singleton(this.field);
    }

    /**
     * Get the field assigned into
     *
     * @return field assigned to
     */
    protected FieldReference getField() {
        return field;
    }
}
