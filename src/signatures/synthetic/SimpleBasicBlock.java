package signatures.synthetic;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.types.TypeReference;

public class SimpleBasicBlock implements ISSABasicBlock {

    private final int firstInstructionIndex;
    private final List<SSAInstruction> instructions;
    private final int number;
    private final IMethod method;
    private final boolean isExit;

    public SimpleBasicBlock(int firstIndex, List<SSAInstruction> instructions, int number, IMethod method,
                                    boolean isExit) {
        this.firstInstructionIndex = firstIndex;
        this.instructions = instructions;
        this.number = number;
        this.method = method;
        this.isExit = isExit;
    }

    public SSAInstruction[] getInstructions() {
        return (SSAInstruction[]) instructions.toArray();
    }

    @Override
    public int getFirstInstructionIndex() {
        return firstInstructionIndex;
    }

    @Override
    public int getLastInstructionIndex() {
        return firstInstructionIndex + instructions.size() - 1;
    }

    @Override
    public IMethod getMethod() {
        return method;
    }

    @Override
    public int getNumber() {
        return number;
    }

    @Override
    public int getGraphNodeId() {
        return number;
    }

    @Override
    public void setGraphNodeId(int number) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<SSAInstruction> iterator() {
        return instructions.iterator();
    }

    @Override
    public boolean isCatchBlock() {
        return false;
    }

    @Override
    public boolean isExitBlock() {
        return isExit;
    }

    @Override
    public boolean isEntryBlock() {
        return getNumber() == 0;
    }

    @Override
    public Iterator<SSAPhiInstruction> iteratePhis() {
        return Collections.emptyIterator();
    }

    @Override
    public Iterator<SSAPiInstruction> iteratePis() {
        return Collections.emptyIterator();
    }

    @Override
    public SSAInstruction getLastInstruction() {
        return instructions.get(instructions.size() - 1);
    }

    @Override
    public Iterator<TypeReference> getCaughtExceptionTypes() {
        return Collections.emptyIterator();
    }
}