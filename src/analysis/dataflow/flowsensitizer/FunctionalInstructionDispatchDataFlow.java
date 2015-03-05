package analysis.dataflow.flowsensitizer;

import java.util.Map;

import analysis.dataflow.InstructionDispatchDataFlow;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.SSAInstruction;

public abstract class FunctionalInstructionDispatchDataFlow<T> extends InstructionDispatchDataFlow<T> {

    public FunctionalInstructionDispatchDataFlow(boolean forward) {
        super(forward);
    }

    public abstract Map<SSAInstruction, AnalysisRecord<T>> runDataFlowAnalysis(IMethod m);

}
