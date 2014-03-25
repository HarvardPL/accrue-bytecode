package analysis.pointer.statements;

import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.LocalNode;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;

/**
 * Points-to statement for an exception throw instruction
 */
public class ThrowStatement extends PointsToStatement {

    /**
     * Points-to graph node for the exception that is thrown
     */
    private final LocalNode exception;
    /**
     * Basic block the instruction occurs in
     */
    private final ISSABasicBlock bb;
    
    /**
     * Points-to statement for an exception throw
     * 
     * @param exception
     *            Points-to graph node for the exception that is thrown
     * @param ir
     *            code for the method the instruction is contained in
     * @param bb
     *            basic block the instruction occurs in
     */
    public ThrowStatement(LocalNode exception, IR ir, ISSABasicBlock bb) {
        super(ir);
        this.exception = exception;
        this.bb = bb;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        ReferenceVariableReplica thrown = new ReferenceVariableReplica(context, exception);     
        List<CatchBlock> catchBlocks = getSuccessorCatchBlocks(bb, registrar, context);
        return checkThrown(exception.getExpectedType(), thrown, g, getExceptionReplicas(context, registrar), catchBlocks);
    }
    
    @Override
    public String toString() {
        return "throw " + exception;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((bb == null) ? 0 : bb.hashCode());
        result = prime * result + ((exception == null) ? 0 : exception.hashCode());
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
        ThrowStatement other = (ThrowStatement) obj;
        if (bb == null) {
            if (other.bb != null)
                return false;
        } else if (!bb.equals(other.bb))
            return false;
        if (exception == null) {
            if (other.exception != null)
                return false;
        } else if (!exception.equals(other.exception))
            return false;
        return true;
    }
}
