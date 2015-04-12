package analysis.pointer.statements;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * Points-to graph statement for an assignment from an array element, v = a[i]
 */
public class ArrayToLocalStatement extends PointsToStatement {

    private final ReferenceVariable value;
    private ReferenceVariable array;

    /**
     * Points-to graph statement for an assignment from an array element, v = a[i]
     *
     * @param v
     *            variable being assigned into
     * @param a
     *            variable for the array being accessed
     * @param m
     */
    protected ArrayToLocalStatement(ReferenceVariable v, ReferenceVariable a, IMethod m) {
        super(m);
        value = v;
        array = a;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, array, haf);
        PointsToGraphNode v = new ReferenceVariableReplica(context, value, haf);

        GraphDelta changed = new GraphDelta(g);

        Iterator<InstanceKey> iter;
        if (delta == null) {
            iter = g.pointsToIterator(a, originator);
        }
        else {
            // we have a delta. Let's be smart about how we use it.
            // Statement is v = a[i]. First check if a points to anything new. If it does now point to some new abstract
            // object k, add everything that k[i] points to to v's set.
            iter = delta.pointsToIterator(a);
        }
        while (iter.hasNext()) {
            InstanceKey arrHeapContext = iter.next();
            IClass base = AnalysisUtil.getClassHierarchy().lookupClass(arrHeapContext.getConcreteType()
                                                                                     .getReference()
                                                                                     .getArrayElementType());
            ObjectField contents = new ObjectField(arrHeapContext,
                                                   arrHeapContext.getConcreteType(),
                                                   PointsToGraph.ARRAY_CONTENTS,
                                                   base);
            GraphDelta d1 = g.copyEdges(contents, v);
            changed = changed.combine(d1);
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
