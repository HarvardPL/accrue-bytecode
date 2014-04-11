package analysis.dataflow.interprocedural.nonnull;

import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.VarContext;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.shrikeBT.IConditionalBranchInstruction.Operator;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAComparisonInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAConversionInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
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
    protected Map<Integer, VarContext<NonNullAbsVal>> call(SSAInvokeInstruction instruction,
            Set<VarContext<NonNullAbsVal>> inItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock bb) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> confluence(Set<VarContext<NonNullAbsVal>> items) {
        // TODO Auto-generated method stub
        return null;
    }
    
    private Map<Integer, VarContext<NonNullAbsVal>> recordNPE(Map<Integer, VarContext<NonNullAbsVal>> outputItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        // Add something as null in the NPE branch and as not null in the normal branch, maybe this should just return a pair
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowThrow(SSAThrowInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowSwitch(SSASwitchInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowReturn(SSAReturnInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowPutField(SSAPutInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Not flow sensitive for fields, so we cannot determine whether they are null or not
        
        // TODO If this succeeds we know the receiver isn't null
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowNewObject(SSANewInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normalOut = in.setLocal(i.getDef(), NonNullAbsVal.NOT_NULL);
        return itemToMapWithExceptions(normalOut, in, current, cfg);
    }
    
    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowNewArray(SSANewInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        VarContext<NonNullAbsVal> normalOut = in.setLocal(i.getDef(), NonNullAbsVal.NOT_NULL);
        return itemToMapWithExceptions(normalOut, in, current, cfg);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowLoadMetadata(SSALoadMetadataInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO handle reflection in NonNullDataFlow
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeVirtual(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeStatic(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeSpecial(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowInvokeInterface(SSAInvokeInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return call(i, previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowGoto(SSAGotoInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowGetField(SSAGetInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // Not flow sensitive for fields, so we cannot determine whether they are null or not
        
        // TODO If this succeeds we know the receiver isn't null
        return mergeAndCreateMap(previousItems, cfg, current);
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowConditionalBranch(SSAConditionalBranchInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        assert getNumSuccs(current, cfg) == 2 : "Not two successors for a conditional branch: "
                + PrettyPrinter.instructionString(currentNode.getIR(), i) + " has " + getNumSuccs(current, cfg);
        
        VarContext<NonNullAbsVal> in = confluence(previousItems);
        Map<Integer, VarContext<NonNullAbsVal>> out = itemToModifiableMap(in, current, cfg);
        
        // check if the comparison is an comparison against the null literal
        IR ir = currentNode.getIR(); 
        boolean fstNull = ir.getSymbolTable().getValue(i.getUse(0)).isNullConstant();
        boolean sndNull = ir.getSymbolTable().getValue(i.getUse(1)).isNullConstant();
        if (fstNull || sndNull) {
            VarContext<NonNullAbsVal> trueContext = in;
            VarContext<NonNullAbsVal> falseContext = in;
            if (fstNull && !sndNull && i.getOperator() == Operator.EQ) {
                // null == obj
                trueContext = in.setLocal(i.getUse(1), NonNullAbsVal.MAYBE_NULL);
                falseContext = in.setLocal(i.getUse(1), NonNullAbsVal.NOT_NULL);
            }
            
            if (fstNull && !sndNull && i.getOperator() == Operator.NE) {
                // null != obj
                trueContext = in.setLocal(i.getUse(1), NonNullAbsVal.NOT_NULL);
                falseContext = in.setLocal(i.getUse(1), NonNullAbsVal.MAYBE_NULL);
            }
            
            if (sndNull && !fstNull && i.getOperator() == Operator.EQ) {
                // obj == null
                trueContext = in.setLocal(i.getUse(0), NonNullAbsVal.MAYBE_NULL);
                falseContext = in.setLocal(i.getUse(0), NonNullAbsVal.NOT_NULL);
            }
            
            if (sndNull && !fstNull && i.getOperator() == Operator.NE) {
                // obj != null
                trueContext = in.setLocal(i.getUse(0), NonNullAbsVal.NOT_NULL);
                falseContext = in.setLocal(i.getUse(0), NonNullAbsVal.MAYBE_NULL);
            }
            
            out.put(getTrueSuccessor(current, cfg), trueContext);
            out.put(getFalseSuccessor(current, cfg), falseContext);
        }
        
        return out;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowCheckCast(SSACheckCastInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowUnaryNegation(SSAUnaryOpInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowPutStatic(SSAPutInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowPhi(SSAPhiInstruction i, Set<VarContext<NonNullAbsVal>> previousItems,
            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowInstanceOf(SSAInstanceofInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowGetCaughtException(SSAGetCaughtExceptionInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowGetStatic(SSAGetInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowConversion(SSAConversionInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowComparison(SSAComparisonInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected VarContext<NonNullAbsVal> flowBinaryOp(SSABinaryOpInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowArrayStore(SSAArrayStoreInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowArrayLoad(SSAArrayLoadInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<Integer, VarContext<NonNullAbsVal>> flowArrayLength(SSAArrayLengthInstruction i,
            Set<VarContext<NonNullAbsVal>> previousItems, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

}
