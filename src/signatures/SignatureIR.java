package signatures;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAIndirectionData;
import com.ibm.wala.ssa.SSAIndirectionData.Name;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAOptions;
import com.ibm.wala.ssa.SymbolTable;

public class SignatureIR extends IR {

    public SignatureIR(IMethod method, SSAInstruction[] instructions, SymbolTable symbolTable, SSACFG cfg,
                                    SSAOptions options) {
        super(method, instructions, symbolTable, cfg, options);
    }

    @Override
    protected SSA2LocalMap getLocalMap() {
        // TODO this might be worth having around, if we can figure out how to get it from the pre-rewrite IR
        // Note that if the pre-rewrite IR is ShrikeIRFactory$1 then this method is public
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
