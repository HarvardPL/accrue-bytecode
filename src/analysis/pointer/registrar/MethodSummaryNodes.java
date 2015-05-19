package analysis.pointer.registrar;

import java.util.ArrayList;
import java.util.List;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.ExitType;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.types.TypeReference;

/**
 * Summary for a particular method. Contains the reference variables for the formal arguments (including "this"), and
 * return value.
 */
public class MethodSummaryNodes {
    /**
     * node for the return value, will be null if void or if the return value has a primitive type
     */
    private final ReferenceVariable returnNode;
    /**
     * Nodes for each type of exception that may be thrown
     */
    private final ReferenceVariable exception;
    /**
     * Formal argument nodes
     */
    private final List<ReferenceVariable> formals;
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
        name = PrettyPrinter.methodString(method);

        TypeReference returnType = method.getReturnType();
        if (!method.getReturnType().isPrimitiveType()) {
            returnNode = ReferenceVariableFactory.createMethodExit(returnType, method, ExitType.NORMAL);
        } else {
            returnNode = null;
        }

        formals = new ArrayList<>(method.getNumberOfParameters());
        for (int i = 0; i < method.getNumberOfParameters(); i++) {
            TypeReference type = method.getParameterType(i);
            if (type.isPrimitiveType()) {
                formals.add(null);
            } else {
                formals.add(ReferenceVariableFactory.createFormal(i, method.getParameterType(i), method));
            }
        }

        exception = ReferenceVariableFactory.createMethodExit(TypeReference.JavaLangThrowable,
                                                              method,
                                                              ExitType.EXCEPTIONAL);
    }

    public ReferenceVariable getReturn() {
        return returnNode;
    }

    public ReferenceVariable getException() {
        return exception;
    }

    /**
     * Get the summary reference variable for the ith formal argument
     *
     * @param i
     *            argument index (by convention the 0th formal is "this" for non-static methods
     * @return Reference variable for the ith formal
     */
    public ReferenceVariable getFormal(int i) {
        return formals.get(i);
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
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        MethodSummaryNodes other = (MethodSummaryNodes) obj;
        if (exception == null) {
            if (other.exception != null) {
                return false;
            }
        } else if (!exception.equals(other.exception)) {
            return false;
        }
        if (name == null) {
            if (other.name != null) {
                return false;
            }
        } else if (!name.equals(other.name)) {
            return false;
        }
        if (returnNode == null) {
            if (other.returnNode != null) {
                return false;
            }
        } else if (!returnNode.equals(other.returnNode)) {
            return false;
        }
        return true;
    }
}
