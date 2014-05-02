package analysis.dataflow.interprocedural.pdg;

import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;

public class PDGContext {

    private final PDGNode returnNode;
    private final PDGNode exceptionNode;
    private final PDGNode pcNode;

    public PDGContext(PDGNode returnNode, PDGNode exceptionNode, PDGNode pcNode) {
        this.returnNode = returnNode;
        this.exceptionNode = exceptionNode;
        this.pcNode = pcNode;
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
        final int prime = 31;
        int result = 1;
        result = prime * result + ((exceptionNode == null) ? 0 : exceptionNode.hashCode());
        result = prime * result + ((pcNode == null) ? 0 : pcNode.hashCode());
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
        if (exceptionNode == null) {
            if (other.exceptionNode != null)
                return false;
        } else if (!exceptionNode.equals(other.exceptionNode))
            return false;
        if (pcNode == null) {
            if (other.pcNode != null)
                return false;
        } else if (!pcNode.equals(other.pcNode))
            return false;
        if (returnNode == null) {
            if (other.returnNode != null)
                return false;
        } else if (!returnNode.equals(other.returnNode))
            return false;
        return true;
    }
}
