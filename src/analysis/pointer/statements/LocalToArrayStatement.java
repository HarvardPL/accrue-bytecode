package analysis.pointer.statements;

import java.util.ArrayList;
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
import analysis.pointer.graph.TypeFilter;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

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
     * Statement for an assignment into an array, a[i] = v. Note that we do not reason about the individual array
     * elements.
     *
     * @param a points-to graph node for array assigned into
     * @param m method the points-to statement came from
     * @param value points-to graph node for assigned value
     */
    public LocalToArrayStatement(ReferenceVariable a, ReferenceVariable v, IMethod m) {
        super(m);
        array = a;
        value = v;
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
            GraphDelta d1;
            if (base != AnalysisUtil.getObjectClass()) {
                TypeFilter filter = TypeFilter.create(base);
                d1 = g.copyFilteredEdges(v, filter, contents);
            }
            else {
                d1 = g.copyEdges(v, contents);
            }
            changed = changed.combine(d1);
        }

        return changed;
    }

    @Override
    public String toString() {
        return array + "." + PointsToGraph.ARRAY_CONTENTS + " = " + value;
    }

    @Override
    public ReferenceVariable getDef() {
        return null;
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
}
