package analysis.pointer.statements;

import java.util.Set;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
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
 * Points-to graph statement for an assignment into an array, a[i] = v
 */
public class LocalToArrayStatement extends PointsToStatement {
    /**
     * Array assigned into
     */
    private final ReferenceVariable array;
    /**
     * Value inserted into array
     */
    private final ReferenceVariable value;
    /**
     * Type of array elements
     */
    private final TypeReference baseType;

    /**
     * Statement for an assignment into an array, a[i] = v. Note that we do not reason about the individual array
     * elements.
     * 
     * @param a
     *            points-to graph node for array assigned into
     * @param value
     *            points-to graph node for assigned value
     * @param baseType
     *            type of the array elements
     * @param m
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    public LocalToArrayStatement(ReferenceVariable a, ReferenceVariable v, TypeReference baseType, IMethod m) {
        super(ir, i);
        this.array = a;
        this.value = v;
        this.baseType = baseType;
    }

    // /**
    // * Statement for array contents assigned to an inner array during multidimensional array creation. This means that
    // * any assignments to the inner array will correctly point to an array with dimension one less than the outer
    // array.
    // * <p>
    // * int[] b = new int[5][4]
    // * <p>
    // * results in
    // * <p>
    // * COMPILER-GENERATED = new int[5]
    // * <p>
    // * b.[contents] = COMPILER-GENERATED
    // *
    // * @param outerArray
    // * points-to graph node for outer array
    // * @param innerArray
    // * points-to graph node for inner array
    // * @param innerArrayType
    // * type of the inner array
    // */
    // public LocalToArrayStatement(ReferenceVariable a, ReferenceVariable v, TypeReference baseType) {
    // super(ir, i);
    // this.array = a;
    // this.value = v;
    // this.baseType = baseType;
    // }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, array);
        PointsToGraphNode v = new ReferenceVariableReplica(context, value);

        Set<InstanceKey> valHeapContexts = g.getPointsToSet(v);

        if (DEBUG && valHeapContexts.isEmpty()) {
            System.err.println("LOCAL: " + v + "\n\t for "
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }

        if (DEBUG && g.getPointsToSet(a).isEmpty()) {
            System.err.println("ARRAY: " + a + "\n\t for "
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }

        boolean changed = false;
        for (InstanceKey arrHeapContext : g.getPointsToSet(a)) {
            ObjectField contents = new ObjectField(arrHeapContext, PointsToGraph.ARRAY_CONTENTS, baseType);
            changed |= g.addEdges(contents, valHeapContexts);
        }

        return changed;
    }

    @Override
    public String toString() {
        return array + "." + PointsToGraph.ARRAY_CONTENTS + " = " + value;
    }
}
