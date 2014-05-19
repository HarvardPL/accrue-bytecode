package signatures.synthetic;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAIndirectionData;
import com.ibm.wala.ssa.SSAIndirectionData.Name;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAOptions;
import com.ibm.wala.ssa.SymbolTable;

public class SyntheticIR extends IR {

    public SyntheticIR(IMethod method, SSAInstruction[] instructions, SymbolTable symbolTable, SSACFG cfg,
                                    SSAOptions options) {
        super(method, instructions, symbolTable, cfg, options);
    }

    @Override
    protected SSA2LocalMap getLocalMap() {
        return null;
    }

    @Override
    protected <T extends Name> SSAIndirectionData<T> getIndirectionData() {
        return null;
    }

    @Override
    protected String instructionPosition(int instructionIndex) {
        /**
         * Can't call this anyway so why bother implementing it
         */
        return "";
    }
}
