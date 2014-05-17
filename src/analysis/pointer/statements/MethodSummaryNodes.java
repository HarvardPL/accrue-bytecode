package analysis.pointer.statements;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.ExitType;
import analysis.pointer.statements.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.types.TypeReference;

/**
 * Summary for a particular method. Contains the reference variables for the
 * formal arguments (including "this"), and return value.
 */
public class MethodSummaryNodes {
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
     * @param method
     *            method these are the summary nodes for
     */
    public MethodSummaryNodes(IMethod method) {
        assert method != null : "method is null";
        name = PrettyPrinter.parseMethod(method);

        TypeReference returnType = method.getReturnType();
        if (!method.getReturnType().isPrimitiveType()) {
            returnNode = ReferenceVariableFactory.getOrCreateMethodExitNode(returnType, method, ExitType.NORMAL);
        } else {
            returnNode = null;
        }

        exception = ReferenceVariableFactory.getOrCreateMethodExitNode(TypeReference.JavaLangThrowable, method,
                                        ExitType.EXCEPTIONAL);
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
        result = prime * result + ((name == null) ? 0 : name.hashCode());
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
        MethodSummaryNodes other = (MethodSummaryNodes) obj;
        if (exception == null) {
            if (other.exception != null)
                return false;
        } else if (!exception.equals(other.exception))
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
        return true;
    }
}
