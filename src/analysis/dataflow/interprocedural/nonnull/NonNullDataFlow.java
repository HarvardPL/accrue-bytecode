package analysis.dataflow.interprocedural.nonnull;

import java.util.Map;
import java.util.Set;

import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.VarContext;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAComparisonInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAConversionInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;

public class NonNullDataFlow extends InterproceduralDataFlow<VarContext<NonNullAbsVal>> {

    public NonNullDataFlow(CGNode currentNode, CallGraph cg, PointsToGraph ptg) {
        super(currentNode, cg, ptg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> call(Set<VarContext<NonNullAbsVal>> inItems,
            SSAInvokeInstruction instruction, SSACFG cfg, ISSABasicBlock bb) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> confluence(Set<VarContext<NonNullAbsVal>> items) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowThrow(SSAThrowInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowSwitch(SSASwitchInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowReturn(SSAReturnInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowPutField(SSAPutInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // strong update would let us sometimes update the abstract locations for
        // the field based on the assigned value but that is not supported
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowNewObject(SSANewInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normalOut = in.recordLocal(i.getDef(), NonNullAbsVal.NOT_NULL);
        return itemToMapWithExceptions(normalOut, in, current, cfg);
    }
    
    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowNewArray(SSANewInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normalOut = in.recordLocal(i.getDef(), NonNullAbsVal.NOT_NULL);
        return itemToMapWithExceptions(normalOut, in, current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowLoadMetadata(SSALoadMetadataInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO handle reflection in NonNullDataFlow
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeVirtual(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO handle NPE
        return call(previousItems, i, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeStatic(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeSpecial(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeInterface(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowGoto(SSAGotoInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowGetField(SSAGetInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowConditionalBranch(SSAConditionalBranchInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowCheckCast(SSACheckCastInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowUnaryNegation(SSAUnaryOpInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowPutStatic(SSAPutInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowPhi(SSAPhiInstruction i, Set<VarContext<NonNullAbsVal>> previousItems,
            SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowInstanceOf(SSAInstanceofInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowGetStatic(SSAGetInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowConversion(SSAConversionInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowComparison(SSAComparisonInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowBinaryOp(SSABinaryOpInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowArrayStore(SSAArrayStoreInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowArrayLoad(SSAArrayLoadInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowArrayLength(SSAArrayLengthInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, SSACFG cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

}
