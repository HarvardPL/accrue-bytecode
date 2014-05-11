package analysis.pointer.statements;

import java.util.Set;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariable;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeReference;

public class ExceptionAssignmentStatement extends PointsToStatement {

    /**
     * Statement for the assignment from a thrown exception to a caught
     * exception or the summary node for the exceptional exit to a method
     * 
     * @param thrown
     *            reference variable for the exception being thrown
     * @param caught
     *            reference variable for the caught exception (or summary for
     *            the method exit)
     * @param i
     *            instruction throwing the exception
     * @param ir
     *            code containing the instruction that throws the exception
     * @param notType
     *            types that the exception being caught cannot have since those
     *            types must have been caught by previous catch blocks
     */
    public ExceptionAssignmentStatement(ReferenceVariable thrown, ReferenceVariable caught, SSAInstruction i, IR ir,
                                    Set<TypeReference> notType) {
        super(ir, i);
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        // TODO Auto-generated method stub
        return false;
    }

}
