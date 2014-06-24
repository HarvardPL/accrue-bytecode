package analysis.pointer.statements;

import java.util.Collections;
import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
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
    protected ArrayToLocalStatement(ReferenceVariable v, ReferenceVariable a, TypeReference baseType, IMethod m) {
        super(m);
        this.value = v;
        this.array = a;
        this.baseType = baseType;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, array);
        PointsToGraphNode v = new ReferenceVariableReplica(context, value);

        boolean changed = false;
        // TODO filter only arrays with assignable base types
        // Might have to subclass InstanceKey to keep more info about arrays
        if (PointsToAnalysis.DEBUG && g.getPointsToSet(a).isEmpty()) {
            System.err.println("ARRAY: " + a + " for " + this + " in " + PrettyPrinter.methodString(getMethod()));
        }

        for (InstanceKey arrHeapContext : g.getPointsToSet(a)) {
            ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType);
            if (PointsToAnalysis.DEBUG && g.getPointsToSet(contents).isEmpty()) {
                System.err.println("CONTENTS: " + contents + " for " + this + " in "
                                                + PrettyPrinter.methodString(getMethod()));
            }
            changed |= g.addEdges(v, g.getPointsToSetFiltered(contents, v.getExpectedType()));
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
