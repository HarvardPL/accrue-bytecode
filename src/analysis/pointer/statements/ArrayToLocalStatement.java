package analysis.pointer.statements;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import util.OrderedPair;
import analysis.AnalysisUtil;
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

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to graph statement for an assignment from an array element, v = a[i]
 */
public class ArrayToLocalStatement extends PointsToStatement {

    private final ReferenceVariable value;
    private ReferenceVariable array;

    /**
     * Points-to graph statement for an assignment from an array element, v = a[i]
     *
     * @param v variable being assigned into
     * @param a variable for the array being accessed
     * @param pp the program point of the statement
     */
    protected ArrayToLocalStatement(ReferenceVariable v, ReferenceVariable a, ProgramPoint pp) {
        super(pp);
        this.value = v;
        this.array = a;
        assert !v.isFlowSensitive();
        assert !a.isFlowSensitive();
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, array, haf);
        PointsToGraphNode v = new ReferenceVariableReplica(context, value, haf);

        GraphDelta changed = new GraphDelta(g);
        // TODO filter only arrays with assignable base types
        // Might have to subclass InstanceKey to keep more info about arrays

        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());
        InterProgramPointReplica post = InterProgramPointReplica.create(context, this.programPoint().post());

        if (delta == null) {
            // let's do the normal processing
            for (Iterator<InstanceKeyRecency> iter = g.pointsToIterator(a, pre, originator); iter.hasNext();) {
                InstanceKeyRecency arrHeapContext = iter.next();
                if (g.isNullInstanceKey(arrHeapContext)) {
                    // The target is null
                    continue;
                }
                IClass base = AnalysisUtil.getClassHierarchy().lookupClass(arrHeapContext.getConcreteType()
                                                                                         .getReference()
                                                                                         .getArrayElementType());
                ObjectField contents = new ObjectField(arrHeapContext,
                                                       arrHeapContext.getConcreteType(),
                                                       PointsToGraph.ARRAY_CONTENTS,
                                                       base);
                assert !contents.isFlowSensitive() : "Trying to use flow sensitive array contents field. Make sure the rest of the code is consistent.";
                GraphDelta d1 = g.copyEdges(contents, pre, v, post);
                // GraphDelta d1 = g.copyFilteredEdges(contents, filter, v);
                changed = changed.combine(d1);
            }
        }
        else {
            // we have a delta. Let's be smart about how we use it.
            // Statement is v = a[i]. First check if a points to anything new. If it does now point to some new abstract
            // object k, add everything that k[i] points to to v's set.
            for (Iterator<InstanceKeyRecency> iter = delta.pointsToIterator(a, pre, originator); iter.hasNext();) {
                InstanceKeyRecency arrHeapContext = iter.next();
                if (g.isNullInstanceKey(arrHeapContext)) {
                    // The target is null
                    continue;
                }
                IClass base = AnalysisUtil.getClassHierarchy().lookupClass(arrHeapContext.getConcreteType()
                                                                                         .getReference()
                                                                                         .getArrayElementType());
                ObjectField contents = new ObjectField(arrHeapContext,
                                                       arrHeapContext.getConcreteType(),
                                                       PointsToGraph.ARRAY_CONTENTS,
                                                       base);
                assert !contents.isFlowSensitive() : "Trying to use flow sensitive array contents field. Make sure the rest of the code is consistent.";

                GraphDelta d1 = g.copyEdges(contents, pre, v, post);
                // GraphDelta d1 = g.copyFilteredEdges(contents, filter, v);
                changed = changed.combine(d1);
            }

            // Note: we do not need to check if there are any k[i]'s that have changed, since that will be
            // taken care of automatically by the subset relations.
        }
        return changed;
    }

    @Override
    public boolean mayKillNode(Context context, PointsToGraph g) {
        return value.isFlowSensitive();
    }

    @Override
    public OrderedPair<Boolean, PointsToGraphNode> killsNode(Context context, PointsToGraph g) {
        if (!value.isFlowSensitive()) {
            return null;
        }
        return new OrderedPair<Boolean, PointsToGraphNode>(Boolean.TRUE, new ReferenceVariableReplica(context,
                                                                                                      value,
                                                                                                      g.getHaf()));
    }

    @Override
    public boolean isImportant() {
        return value.isFlowSensitive();
    }

    @Override
    public String toString() {
        return value + " = " + array + "." + PointsToGraph.ARRAY_CONTENTS;
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0;
        array = newVariable;
    }

    @Override
    public List<ReferenceVariable> getDefs() {
        return Collections.singletonList(value);
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.singletonList(array);
    }

    @Override
    public boolean mayChangeOrUseFlowSensPointsToGraph() {
        assert !this.value.isFlowSensitive();
        assert !this.array.isFlowSensitive();

        // if the local has local scope, we need to track where it is defined.
        return value.hasLocalScope();
    }
}
