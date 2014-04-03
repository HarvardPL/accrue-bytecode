package analysis.pointer.graph;

import java.util.LinkedList;
import java.util.List;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.pointer.statements.StatementRegistrar;

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
    private final ReferenceVariable thisNode;
    /**
     * Node for the formal arguments (a formal will be null if it has a
     * primitive type)
     */
    private final List<ReferenceVariable> formals;
    /**
     * node for the return value, will be null if void or if the return value
     * has a primitive type
     */
    private final ReferenceVariable returnNode;
    /**
     * Nodes for each type of exception that may be thrown
     */
    private final ReferenceVariable exception;
    /**
     * String for method
     */
    private final String name;

    /**
     * Create nodes for formals and return values
     * 
     * @param registrar
     *            points-to statement registrar
     * @param ir
     *            IR for the code
     */
    public MethodSummaryNodes(StatementRegistrar registrar, IR ir) {
        if (ir == null) {
            System.out.println("IR is null");
        }

        boolean isStatic = ir.getMethod().isStatic();
        thisNode = isStatic ? null : registrar.getLocal(ir.getParameter(0), ir);

        formals = new LinkedList<>();
        if (isStatic) {
            if (ir.getNumberOfParameters() > 0) {
                // if static then the first argument is the 0th parameter
                int arg = ir.getParameter(0);
                if (TypeRepository.getType(arg, ir).isPrimitiveType()) {
                    formals.add(null);
                } else {
                    formals.add(registrar.getLocal(ir.getParameter(0), ir));
                }
            }
        }
        for (int i = 1; i < ir.getNumberOfParameters(); i++) {
            int arg = ir.getParameter(i);
            if (TypeRepository.getType(arg, ir).isPrimitiveType()) {
                formals.add(null);
            } else {
                formals.add(registrar.getLocal(ir.getParameter(i), ir));
            }
        }

        name = PrettyPrinter.parseMethod(ir.getMethod().getReference());

        TypeReference returnType = ir.getMethod().getReturnType();
        returnNode = (isVoid(returnType) || returnType.isPrimitiveType()) ? null :
        // TODO this and below are the only places this is called outside of the
        // Registrar
                new ReferenceVariable(name + "-EXIT", returnType, false);

        TypeReference throwable = TypeReference.JavaLangThrowable;
        exception = new ReferenceVariable(name + "-EXCEPTION", throwable, false);
    }

    private boolean isVoid(TypeReference type) {
        return type == TypeReference.Void;
    }

    public ReferenceVariable getThisNode() {
        return thisNode;
    }

    public List<ReferenceVariable> getFormals() {
        return formals;
    }

    public ReferenceVariable getReturnNode() {
        return returnNode;
    }

    public ReferenceVariable getException() {
        return exception;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((exception == null) ? 0 : exception.hashCode());
        result = prime * result + ((formals == null) ? 0 : formals.hashCode());
        result = prime * result + ((name == null) ? 0 : name.hashCode());
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
        if (exception == null) {
            if (other.exception != null)
                return false;
        } else if (!exception.equals(other.exception))
            return false;
        if (formals == null) {
            if (other.formals != null)
                return false;
        } else if (!formals.equals(other.formals))
            return false;
        if (name == null) {
            if (other.name != null)
                return false;
        } else if (!name.equals(other.name))
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
