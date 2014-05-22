package analysis.pointer.statements;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Points-to graph statement for an assignment from an array element, v = a[i]
 */
public class ArrayToLocalStatement extends PointsToStatement {

    private final ReferenceVariable value;
    private final ReferenceVariable array;
    private final TypeReference baseType;

    /**
     * Points-to graph statement for an assignment from an array element, v =
     * a[i]
     * 
     * @param v
     *            Points-to graph node for the assignee
     * @param a
     *            Points-to graph node for the array being accessed
     * @param baseType
     *            base type of the array
     * @param ir
     *            Code this statement occurs in
     * @param i
     *            Instruction that generated this points-to statement
     */
    protected ArrayToLocalStatement(ReferenceVariable v, ReferenceVariable a, TypeReference baseType, IR ir,
                                    SSAArrayLoadInstruction i) {
        super(ir, i);
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
        if (DEBUG && g.getPointsToSet(a).isEmpty()) {
            System.err.println("ARRAY: " + a + "\n\t for "
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }

        for (InstanceKey arrHeapContext : g.getPointsToSet(a)) {
            ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType);
            if (DEBUG && g.getPointsToSet(contents).isEmpty()) {
                System.err.println("ARRAY CONTENTS: " + contents + "\n\t for "
                                                + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                                + PrettyPrinter.methodString(getCode().getMethod()));
            }
            changed |= g.addEdges(v, g.getPointsToSet(contents));
        }

        return changed;
    }

    @Override
    public String toString() {
        return value + " = " + array + "." + PointsToGraph.ARRAY_CONTENTS;
    }
}
