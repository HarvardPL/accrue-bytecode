package analysis.pointer.statements;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.statements.ReferenceVariableFactory.ReferenceVariable;

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
    public ArrayToLocalStatement(ReferenceVariable v, ReferenceVariable a, TypeReference baseType, IR ir,
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

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((array == null) ? 0 : array.hashCode());
        result = prime * result + ((baseType == null) ? 0 : baseType.hashCode());
        result = prime * result + ((value == null) ? 0 : value.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        ArrayToLocalStatement other = (ArrayToLocalStatement) obj;
        if (array == null) {
            if (other.array != null)
                return false;
        } else if (!array.equals(other.array))
            return false;
        if (baseType == null) {
            if (other.baseType != null)
                return false;
        } else if (!baseType.equals(other.baseType))
            return false;
        if (value == null) {
            if (other.value != null)
                return false;
        } else if (!value.equals(other.value))
            return false;
        return true;
    }
}
