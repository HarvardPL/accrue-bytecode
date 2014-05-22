package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;

/**
 * Defines how to process points-to graph information for a particular statement
 */
public abstract class PointsToStatement {
    /**
     * Code for the method the points-to statement came from
     */
    private final IR ir;
    /**
     * Instruction that generated this points-to statement
     */
    private final SSAInstruction i;
    /**
     * Basic block this points-to statement was generated in
     */
    private ISSABasicBlock bb = null;
    
    public static boolean DEBUG = false;

    /**
     * Create a new points-to statement
     * 
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    protected PointsToStatement(IR ir, SSAInstruction i) {
        this.ir = ir;
        this.i = i;
    }

    /**
     * Process this statement, modifying the points-to graph if necessary
     * 
     * @param context
     *            current analysis context
     * @param haf
     *            factory for creating new analysis contexts
     * @param g
     *            points-to graph (may be modified)
     * @param registrar
     *            Points-to statement registrar
     * @return true if the points-to graph was modified
     */
    public abstract boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g,
            StatementRegistrar registrar);

    /**
     * Get the code for the method this points-to statement was created in
     * 
     * @return intermediate representation code for the method
     */
    public final IR getCode() {
        return ir;
    }

    /**
     * Get the instruction that generated this points-to statement
     * 
     * @return SSA instruction
     */
    public final SSAInstruction getInstruction() {
        return i;
    }

    /**
     * Get the containing basic block
     * 
     * @return basic block this statement was generated in
     */
    public final ISSABasicBlock getBasicBlock() {
        if (bb == null) {
            bb = ir.getBasicBlockForInstruction(i);
        }
        return bb;
    }

    @Override
    public final int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public final boolean equals(Object obj) {
        return this == obj;
    }

    @Override
    public abstract String toString();
}
