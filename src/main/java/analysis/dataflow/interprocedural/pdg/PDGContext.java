package analysis.dataflow.interprocedural.pdg;

import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;

/**
 * Program counter node at the exit of a basic block. Also PDG nodes representing the values of the return value and
 * exception when leaving that basic block.
 */
public class PDGContext {

    private final PDGNode returnNode;
    private final PDGNode exceptionNode;
    private final PDGNode pcNode;
    private final int memoizedHashCode;

    /**
     * Create a context containing PDG nodes for values and the program-counter when exiting a basic block
     * 
     * @param returnNode
     *            node representing a value returned from a method if this is a normal termination edge to the exit
     *            block for a method with non-void return, null otherwise
     * @param exceptionNode
     *            node representing an exception if this is for an exception edge leaving a basic block
     * @param pcNode
     *            Program counter node representing control-flow reaching the exit of the basic block on a particular
     *            edge (note that a new node is not created unless control flow branches, so this node may be the same
     *            as the exit from a predecessor block)
     */
    public PDGContext(PDGNode returnNode, PDGNode exceptionNode, PDGNode pcNode) {
        assert pcNode != null;
        this.returnNode = returnNode;
        this.exceptionNode = exceptionNode;
        this.pcNode = pcNode;
        this.memoizedHashCode = computeHashCode();
    }


    public PDGNode getExceptionNode() {
        return exceptionNode;
    }

    public PDGNode getPCNode() {
        return pcNode;
    }

    public PDGNode getReturnNode() {
        return returnNode;
    }

    @Override
    public int hashCode() {
        return memoizedHashCode;
    }

    private int computeHashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((exceptionNode == null) ? 0 : exceptionNode.hashCode());
        result = prime * result + pcNode.hashCode();
        result = prime * result + ((returnNode == null) ? 0 : returnNode.hashCode());
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
        PDGContext other = (PDGContext) obj;
        if (!pcNode.equals(other.pcNode))
            return false;
        if (exceptionNode == null) {
            if (other.exceptionNode != null)
                return false;
        } else if (!exceptionNode.equals(other.exceptionNode))
            return false;
        if (returnNode == null) {
            if (other.returnNode != null)
                return false;
        } else if (!returnNode.equals(other.returnNode))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "{" + returnNode + ", " + exceptionNode + ", " + pcNode + "}";
    }
}
