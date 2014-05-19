package signatures.synthetic;

import java.util.Collections;

import com.ibm.wala.cfg.AbstractCFG;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.SSAInstruction;

/**
 * Control flow graph with a single non-entry/non-exit node i.e. no branches
 */
public class StraightLineCFG extends AbstractCFG<SSAInstruction, SimpleBasicBlock> {

    private SimpleBasicBlock middleBlock;
    private boolean alreadyAdded = false;

    public StraightLineCFG(IMethod m) {
        super(m);
    }

    @Override
    public void addNode(SimpleBasicBlock n) {
        assert n.getNumber() == 1;
        assert !alreadyAdded;
        middleBlock = n;
        alreadyAdded = true;

        super.addNode(new SimpleBasicBlock(0, Collections.<SSAInstruction> emptyList(), 0, getMethod(), false));
        super.addNode(n);
        super.addNode(new SimpleBasicBlock(0, Collections.<SSAInstruction> emptyList(), 2, getMethod(), true));
        super.init();
        super.addNormalEdge(entry(), middleBlock);
        super.addNormalEdge(middleBlock, exit());
    }

    @Override
    public SimpleBasicBlock getBlockForInstruction(int index) {
        return middleBlock;
    }

    @Override
    public SSAInstruction[] getInstructions() {
        return middleBlock.getInstructions();
    }

    @Override
    public int getProgramCounter(int index) {
        return index;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((middleBlock == null) ? 0 : middleBlock.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        StraightLineCFG other = (StraightLineCFG) obj;
        if (middleBlock == null) {
            if (other.middleBlock != null)
                return false;
        } else if (!middleBlock.equals(other.middleBlock))
            return false;
        return true;
    }

    @Override
    public void removeNodeAndEdges(SimpleBasicBlock n) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeNode(SimpleBasicBlock n) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addEdge(SimpleBasicBlock src, SimpleBasicBlock dst) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeEdge(SimpleBasicBlock src, SimpleBasicBlock dst) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeAllIncidentEdges(SimpleBasicBlock node) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeIncomingEdges(SimpleBasicBlock node) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeOutgoingEdges(SimpleBasicBlock node) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
