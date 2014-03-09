package pointer.statements;

import java.util.LinkedList;
import java.util.List;

import pointer.graph.LocalNode;

import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.TypeReference;

/**
 * Summary for a particular method. Contains the reference variables for the
 * formal arguments (including "this"), and return value.
 */
public class MethodSummaryNodes {

    /**
     * Node for "this" or null if this is a static call
     */
    private final LocalNode thisNode;
    /**
     * Node for the formal arguments (a formal will be null if it has a
     * primitive type)
     */
    private final List<LocalNode> formals;
    /**
     * node for the return value, will be null if void or if the return value
     * has a primitive type
     */
    private final LocalNode returnNode;

    /**
     * Create nodes for formals and return values
     * 
     * @param registrar
     *            points-to statement registrar
     * @param ir
     *            IR for the code
     */
    protected MethodSummaryNodes(StatementRegistrar registrar, IR ir) {
        
        boolean isStatic = ir.getMethod().isStatic();
        thisNode = isStatic ? null : registrar.getLocal(ir.getParameter(0), ir);

        formals = new LinkedList<>();
        if (isStatic) {
            if (ir.getNumberOfParameters() > 0) {
                // if static then the first argument is the 0th parameter
                formals.add(registrar.getLocal(ir.getParameter(0), ir));
            }
        }
        for (int i = 1; i < ir.getNumberOfParameters(); i++) {
            formals.add(registrar.getLocal(ir.getParameter(i), ir));
        }

        TypeReference returnType = ir.getMethod().getReturnType();
        returnNode = (isVoid(returnType) || returnType.isPrimitiveType()) ? null : 
            new LocalNode(ir.getMethod().getSignature() + " return", returnType,false);       
    }

    private boolean isVoid(TypeReference type) {
        return type == TypeReference.Void;
    }

    public LocalNode getThisNode() {
        return thisNode;
    }

    public List<LocalNode> getFormals() {
        return formals;
    }

    public LocalNode getReturnNode() {
        return returnNode;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((formals == null) ? 0 : formals.hashCode());
        result = prime * result + ((returnNode == null) ? 0 : returnNode.hashCode());
        result = prime * result + ((thisNode == null) ? 0 : thisNode.hashCode());
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
        MethodSummaryNodes other = (MethodSummaryNodes) obj;
        if (formals == null) {
            if (other.formals != null)
                return false;
        } else if (!formals.equals(other.formals))
            return false;
        if (returnNode == null) {
            if (other.returnNode != null)
                return false;
        } else if (!returnNode.equals(other.returnNode))
            return false;
        if (thisNode == null) {
            if (other.thisNode != null)
                return false;
        } else if (!thisNode.equals(other.thisNode))
            return false;
        return true;
    }
}
