package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.ReferenceVariable;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAThrowInstruction;

/**
 * Points-to statement for an exception throw instruction
 */
public class ThrowStatement extends PointsToStatement {

    /**
     * Points-to graph node for the exception that is thrown
     */
    private final ReferenceVariable exception;

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
    public ThrowStatement(ReferenceVariable exception, IR ir, SSAThrowInstruction i) {
        super(ir, i);
        this.exception = exception;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        
        ReferenceVariableReplica thrown = new ReferenceVariableReplica(context, exception);
        
        // If ex is null throws a NullPointerException instead of ex
        boolean changed = checkAllThrown(context, g, registrar);

        return changed || checkThrown(exception.getExpectedType(), thrown, context, g, registrar);
    }

    @Override
    public String toString() {
        return "throw " + exception;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
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
        if (exception == null) {
            if (other.exception != null)
                return false;
        } else if (!exception.equals(other.exception))
            return false;
        return true;
    }
}
