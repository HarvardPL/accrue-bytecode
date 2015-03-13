package analysis.pointer.statements;

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
import com.ibm.wala.types.TypeReference;

/**
 * Points-to graph statement for an assignment from an array element, v = a[i]
 */
public class ArrayToLocalStatement extends PointsToStatement {

    private final ReferenceVariable value;
    private ReferenceVariable array;
    private final TypeReference baseType;

    /**
     * Points-to graph statement for an assignment from an array element, v = a[i]
     *
     * @param v
     *            variable being assigned into
     * @param a
     *            variable for the array being accessed
     * @param baseType
     *            base type of the array
     * @param m
     */
    protected ArrayToLocalStatement(ReferenceVariable v, ReferenceVariable a,
            TypeReference baseType, IMethod m) {
        super(m);
        value = v;
        array = a;
        this.baseType = baseType;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, array, haf);
        PointsToGraphNode v = new ReferenceVariableReplica(context, value, haf);

        GraphDelta changed = new GraphDelta(g);
        // TODO filter only arrays with assignable base types
        // Might have to subclass InstanceKey to keep more info about arrays

        if (delta == null) {
            // let's do the normal processing
            for (Iterator<InstanceKey> iter = g.pointsToIterator(a, originator); iter.hasNext();) {
                InstanceKey arrHeapContext = iter.next();
                ObjectField contents =
                        new ObjectField(arrHeapContext,
                                        PointsToGraph.ARRAY_CONTENTS,
                                        baseType);
                GraphDelta d1 = g.copyEdges(contents, v);
                // GraphDelta d1 = g.copyFilteredEdges(contents, filter, v);
                changed = changed.combine(d1);
            }
        }
        else {
            // we have a delta. Let's be smart about how we use it.
            // Statement is v = a[i]. First check if a points to anything new. If it does now point to some new abstract
            // object k, add everything that k[i] points to to v's set.
            for (Iterator<InstanceKey> iter = delta.pointsToIterator(a); iter.hasNext();) {
                InstanceKey arrHeapContext = iter.next();
                ObjectField contents =
                        new ObjectField(arrHeapContext,
                                        PointsToGraph.ARRAY_CONTENTS,
                                        baseType);
                GraphDelta d1 = g.copyEdges(contents, v);
                // GraphDelta d1 = g.copyFilteredEdges(contents, filter, v);
                changed = changed.combine(d1);
            }

            // Note: we do not need to check if there are any k[i]'s that have changed, since that will be
            // taken care of automatically by the subset relations.
        }
        return changed;
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
    public ReferenceVariable getDef() {
        return value;
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.singletonList(array);
    }
}
