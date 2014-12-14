package analysis.pointer.statements;

import java.util.ArrayList;
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
import com.ibm.wala.types.TypeReference;

/**
 * Points-to graph statement for an assignment into an array, a[i] = v
 */
public class LocalToArrayStatement extends PointsToStatement {
    /**
     * Array assigned into
     */
    private ReferenceVariable array;
    /**
     * Value inserted into array
     */
    private ReferenceVariable value;
    /**
     * Type of array elements
     */
    private final TypeReference baseType;

    /**
     * Statement for an assignment into an array, a[i] = v. Note that we do not reason about the individual array
     * elements.
     *
     * @param a points-to graph node for array assigned into
     * @param value points-to graph node for assigned value
     * @param baseType type of the array elements
     * @param pp program point the points-to statement came from
     */
    public LocalToArrayStatement(ReferenceVariable a, ReferenceVariable v, TypeReference baseType, ProgramPoint pp) {
        super(pp);
        assert !v.isFlowSensitive();
        assert !a.isFlowSensitive();
        array = a;
        value = v;
        this.baseType = baseType;
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, array, haf);
        PointsToGraphNode v = new ReferenceVariableReplica(context, value, haf);

        GraphDelta changed = new GraphDelta(g);

        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());
        InterProgramPointReplica post = InterProgramPointReplica.create(context, this.programPoint().post());

        if (delta == null) {
            // no changes, let's do the processing in a straightforward way.
            for (Iterator<InstanceKeyRecency> iter = g.pointsToIterator(a, pre, originator); iter.hasNext();) {
                InstanceKeyRecency arrHeapContext = iter.next();
                ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType, true);

                // contents should never be flow sensitive, since it can never be a singleton
                assert !contents.isFlowSensitive();

                GraphDelta d1 = g.copyEdges(v, pre, contents, post);
                changed = changed.combine(d1);
            }
        }
        else {
            // delta is non null. Let's do this smart!
            // We check if a has changed what it points to. If it has, we need to make the new object fields
            // point to everything that the RHS can.
            for (Iterator<InstanceKeyRecency> iter = delta.pointsToIterator(a, pre, originator); iter.hasNext();) {
                InstanceKeyRecency arrHeapContext = iter.next();
                ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType, true);

                // contents should never be flow sensitive, since it can never be a singleton
                assert !contents.isFlowSensitive() : "Contents should never be flow sensitive";

                GraphDelta d1 = g.copyEdges(v, pre, contents, post);
                changed = changed.combine(d1);
            }
        }

        return changed;
    }

    @Override
    public OrderedPair<Boolean, PointsToGraphNode> killsNode(Context context, PointsToGraph g) {
        return new OrderedPair<Boolean, PointsToGraphNode>(Boolean.TRUE, array.isFlowSensitive()
                ? new ReferenceVariableReplica(context, array, g.getHaf()) : null);
    }

    @Override
    public String toString() {
        return array + "." + PointsToGraph.ARRAY_CONTENTS + " = " + value;
    }

    @Override
    public List<ReferenceVariable> getDefs() {
        return Collections.emptyList();
    }

    @Override
    public List<ReferenceVariable> getUses() {
        List<ReferenceVariable> uses = new ArrayList<>(2);
        uses.add(array);
        uses.add(value);
        return uses;
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0 || useNumber == 1;
        if (useNumber == 0) {
            array = newVariable;
            return;
        }
        value = newVariable;
    }

    @Override
    public boolean mayChangeOrUseFlowSensPointsToGraph() {
        // array contents are never singleton fields, and so
        // it is always a flow insensitive source node.
        // However, if either the value has local scope, we need to track that usage.
        return value.hasLocalScope();
    }

}
