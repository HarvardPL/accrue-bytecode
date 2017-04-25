package analysis.pointer.graph;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import analysis.AnalysisUtil;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.CodeScanner;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.types.FieldReference;

/**
 * Context Interpreter that uses signatures where available
 */
public class AnalysisContextInterpreter implements SSAContextInterpreter {

    @Override
    public Iterator<NewSiteReference> iterateNewSites(CGNode node) {
        assert node != null;
        try {
            if (AnalysisUtil.hasSignature(node.getMethod())) {
                IR sig = AnalysisUtil.getIR(node);
                return sig.iterateNewSites();
            }
            return CodeScanner.getNewSites(node.getMethod()).iterator();
        } catch (InvalidClassFileException e) {
            assert false;
            return null;
        }
    }

    @Override
    public Iterator<FieldReference> iterateFieldsRead(CGNode node) {
        assert node != null;
        try {
            if (AnalysisUtil.hasSignature(node.getMethod())) {
                IR sig = AnalysisUtil.getIR(node);

                List<FieldReference> read = new LinkedList<>();
                Iterator<SSAInstruction> ins = sig.iterateNormalInstructions();
                while (ins.hasNext()) {
                    SSAInstruction i = ins.next();
                    if (i instanceof SSAGetInstruction) {
                        read.add(((SSAGetInstruction) i).getDeclaredField());
                    }
                }
            }
            return CodeScanner.getFieldsRead(node.getMethod()).iterator();
        } catch (InvalidClassFileException e) {
            assert false;
            return null;
        }
    }

    @Override
    public Iterator<FieldReference> iterateFieldsWritten(CGNode node) {
        assert node != null;
        try {
            if (AnalysisUtil.hasSignature(node.getMethod())) {
                IR sig = AnalysisUtil.getIR(node);

                List<FieldReference> written = new LinkedList<>();
                Iterator<SSAInstruction> ins = sig.iterateNormalInstructions();
                while (ins.hasNext()) {
                    SSAInstruction i = ins.next();
                    if (i instanceof SSAPutInstruction) {
                        written.add(((SSAPutInstruction) i).getDeclaredField());
                    }
                }
            }
            return CodeScanner.getFieldsWritten(node.getMethod()).iterator();
        } catch (InvalidClassFileException e) {
            assert false;
            return null;
        }
    }

    @Override
    public boolean recordFactoryType(CGNode node, IClass klass) {
        // TODO Factory types not tracked
        return false;
    }

    @Override
    public boolean understands(CGNode node) {
        return true;
    }

    @Override
    public Iterator<CallSiteReference> iterateCallSites(CGNode node) {
        assert node != null;
        try {
            if (AnalysisUtil.hasSignature(node.getMethod())) {
                IR sig = AnalysisUtil.getIR(node);
                return sig.iterateCallSites();
            }
            return CodeScanner.getCallSites(node.getMethod()).iterator();
        } catch (InvalidClassFileException e) {
            assert false;
            return null;
        }
    }

    @Override
    public IR getIR(CGNode node) {
        return AnalysisUtil.getIR(node);
    }

    @Override
    public IR getIRView(CGNode node) {
        return getIR(node);
    }

    @Override
    public DefUse getDU(CGNode node) {
        return AnalysisUtil.getDefUse(node.getMethod());
    }

    @Override
    public int getNumberOfStatements(CGNode node) {
        IR ir = getIR(node);
        if (ir == null) {
            return -1;
        }
        return ir.getInstructions().length;
    }

    @Override
    public ControlFlowGraph<SSAInstruction, ISSABasicBlock> getCFG(CGNode n) {
        IR ir = getIR(n);
        if (ir == null) {
            return null;
        }
        return ir.getControlFlowGraph();
    }

}
