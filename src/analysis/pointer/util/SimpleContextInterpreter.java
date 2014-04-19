package analysis.pointer.util;

import java.util.Iterator;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.CodeScanner;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.debug.Assertions;

public class SimpleContextInterpreter implements SSAContextInterpreter {
    
    private final AnalysisCache cache;
    private final AnalysisOptions options;

    public SimpleContextInterpreter(AnalysisOptions options, AnalysisCache cache) {
        this.cache = cache;
        this.options = options;
    }

    @Override
    public Iterator<NewSiteReference> iterateNewSites(CGNode node) {
        if (node == null) {
            throw new IllegalArgumentException("node is null");
          }
          try {
            return CodeScanner.getNewSites(node.getMethod()).iterator();
          } catch (InvalidClassFileException e) {
            e.printStackTrace();
            Assertions.UNREACHABLE();
            return null;
          }
    }

    @Override
    public Iterator<FieldReference> iterateFieldsRead(CGNode node) {
        if (node == null) {
            throw new IllegalArgumentException("node is null");
          }
          try {
            return CodeScanner.getFieldsRead(node.getMethod()).iterator();
          } catch (InvalidClassFileException e) {
            e.printStackTrace();
            Assertions.UNREACHABLE();
            return null;
          }
    }

    @Override
    public Iterator<FieldReference> iterateFieldsWritten(CGNode node) {
        if (node == null) {
            throw new IllegalArgumentException("node is null");
          }
          try {
            return CodeScanner.getFieldsWritten(node.getMethod()).iterator();
          } catch (InvalidClassFileException e) {
            e.printStackTrace();
            Assertions.UNREACHABLE();
            return null;
          }
    }

    @Override
    public boolean recordFactoryType(CGNode node, IClass klass) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean understands(CGNode node) {
        return true;
    }

    @Override
    public Iterator<CallSiteReference> iterateCallSites(CGNode node) {
        return getIR(node).iterateCallSites();
    }

    @Override
    public IR getIR(CGNode node) {
        if (node == null) {
            throw new IllegalArgumentException("node is null");
          }
          return cache.getSSACache().findOrCreateIR(node.getMethod(), node.getContext(), options.getSSAOptions());
    }

    @Override
    public DefUse getDU(CGNode node) {
        return node.getDU();
    }

    @SuppressWarnings("deprecation")
    @Override
    public int getNumberOfStatements(CGNode node) {
        return getIR(node).getInstructions().length;
    }

    @Override
    public ControlFlowGraph<SSAInstruction, ISSABasicBlock> getCFG(CGNode n) {
        return getIR(n).getControlFlowGraph();
    }

}
