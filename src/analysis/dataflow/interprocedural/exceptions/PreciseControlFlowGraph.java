package analysis.dataflow.interprocedural.exceptions;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.debug.UnimplementedError;
import com.ibm.wala.util.intset.BitVector;
import com.ibm.wala.util.intset.IntSet;

/**
 * Call graph with labels for exception type on the edges
 * 
 * TODO may have to make this modifiable to get the right edges in the graph
 */
public class PreciseControlFlowGraph implements ControlFlowGraph<SSAInstruction, ISSABasicBlock> {
    private final SSACFG delegate;
    private final Map<OrderedPair<Integer, Integer>, Set<TypeReference>> exEdges;

    public PreciseControlFlowGraph(SSACFG delegate) {
        this.delegate = delegate;
        exEdges = new HashMap<>();
    }

    public Set<TypeReference> getExceptionsForEdge(ISSABasicBlock source, ISSABasicBlock target) {
        if (getNormalSuccessors(source).contains(target)) {
            return Collections.emptySet();
        }
        return getExceptionsForEdge(source.getGraphNodeId(), target.getGraphNodeId());
    }

    private Set<TypeReference> getExceptionsForEdge(int source, int target) {
        OrderedPair<Integer, Integer> edge = new OrderedPair<>(source, target);
        Set<TypeReference> types = exEdges.get(edge);
        return types == null ? Collections.<TypeReference> emptySet() : types;
    }

    protected void addException(int source, int target, TypeReference type) {
        OrderedPair<Integer, Integer> edge = new OrderedPair<>(source, target);
        Set<TypeReference> types = exEdges.get(edge);
        if (types == null) {
            types = new LinkedHashSet<>();
        }
        types.add(type);
    }

    @Override
    public BasicBlock getBlockForInstruction(int instructionIndex) {
        return delegate.getBlockForInstruction(instructionIndex);
    }

    @Override
    public SSAInstruction[] getInstructions() {
        return delegate.getInstructions();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    @Override
    public BitVector getCatchBlocks() {
        return delegate.getCatchBlocks();
    }

    @Override
    public BasicBlock entry() {
        return delegate.entry();
    }

    @Override
    public BasicBlock exit() {
        return delegate.exit();
    }

    @Override
    public int getNumber(ISSABasicBlock b) throws IllegalArgumentException {
        return delegate.getNumber(b);
    }

    @Override
    public BasicBlock getNode(int number) {
        return delegate.getNode(number);
    }

    @Override
    public int getMaxNumber() {
        return delegate.getMaxNumber();
    }

    @Override
    public Iterator<ISSABasicBlock> iterator() {
        return delegate.iterator();
    }

    @Override
    public int getNumberOfNodes() {
        return delegate.getNumberOfNodes();
    }

    @Override
    public Iterator<ISSABasicBlock> getPredNodes(ISSABasicBlock b) throws IllegalArgumentException {
        return delegate.getPredNodes(b);
    }

    @Override
    public int getPredNodeCount(ISSABasicBlock b) throws IllegalArgumentException {
        return delegate.getPredNodeCount(b);
    }

    @Override
    public Iterator<ISSABasicBlock> getSuccNodes(ISSABasicBlock b) throws IllegalArgumentException {
        return delegate.getSuccNodes(b);
    }

    @Override
    public int getSuccNodeCount(ISSABasicBlock b) throws IllegalArgumentException {
        return delegate.getSuccNodeCount(b);
    }

    @Override
    public void addNode(ISSABasicBlock n) throws UnsupportedOperationException {
        delegate.addNode(n);
    }

    @Override
    public void addEdge(ISSABasicBlock src, ISSABasicBlock dst) throws UnsupportedOperationException {
        delegate.addEdge(src, dst);
    }

    @Override
    public void removeEdge(ISSABasicBlock src, ISSABasicBlock dst) throws UnsupportedOperationException {
        delegate.removeEdge(src, dst);
    }

    @Override
    public void removeAllIncidentEdges(ISSABasicBlock node) throws UnsupportedOperationException {
        delegate.removeAllIncidentEdges(node);
    }

    @Override
    public void removeNodeAndEdges(ISSABasicBlock N) throws UnsupportedOperationException {
        delegate.removeNodeAndEdges(N);
    }

    @Override
    public void removeNode(ISSABasicBlock n) throws UnsupportedOperationException {
        delegate.removeNode(n);
    }

    @Override
    public int getProgramCounter(int index) {
        return delegate.getProgramCounter(index);
    }

    @Override
    public boolean containsNode(ISSABasicBlock N) {
        return delegate.containsNode(N);
    }

    @Override
    public IMethod getMethod() {
        return delegate.getMethod();
    }

    @Override
    public List<ISSABasicBlock> getExceptionalSuccessors(ISSABasicBlock b) {
        return delegate.getExceptionalSuccessors(b);
    }

    @Override
    public Collection<ISSABasicBlock> getExceptionalPredecessors(ISSABasicBlock b) {
        return delegate.getExceptionalPredecessors(b);
    }

    @Override
    public Collection<ISSABasicBlock> getNormalSuccessors(ISSABasicBlock b) {
        return delegate.getNormalSuccessors(b);
    }

    @Override
    public Collection<ISSABasicBlock> getNormalPredecessors(ISSABasicBlock b) {
        return delegate.getNormalPredecessors(b);
    }

    @Override
    public Iterator<ISSABasicBlock> iterateNodes(IntSet s) {
        return delegate.iterateNodes(s);
    }

    @Override
    public void removeIncomingEdges(ISSABasicBlock node) throws UnsupportedOperationException {
        delegate.removeIncomingEdges(node);
    }

    @Override
    public void removeOutgoingEdges(ISSABasicBlock node) throws UnsupportedOperationException {
        delegate.removeOutgoingEdges(node);
    }

    @Override
    public boolean hasEdge(ISSABasicBlock src, ISSABasicBlock dst) throws UnimplementedError {
        return delegate.hasEdge(src, dst);
    }

    @Override
    public IntSet getSuccNodeNumbers(ISSABasicBlock b) throws IllegalArgumentException {
        return delegate.getSuccNodeNumbers(b);
    }

    @Override
    public IntSet getPredNodeNumbers(ISSABasicBlock node) throws UnimplementedError {
        return delegate.getPredNodeNumbers(node);
    }
}
