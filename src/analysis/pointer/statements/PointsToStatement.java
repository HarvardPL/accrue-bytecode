package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;

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
    public PointsToStatement(IR ir, SSAInstruction i) {
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
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((i == null) ? 0 : i.hashCode());
        result = prime * result + ((ir == null) ? 0 : ir.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        PointsToStatement other = (PointsToStatement) obj;
        if (i == null) {
            if (other.i != null)
                return false;
        } else if (!i.equals(other.i))
            return false;
        if (ir == null) {
            if (other.ir != null)
                return false;
        } else if (!ir.equals(other.ir))
            return false;
        return true;
    }
}
