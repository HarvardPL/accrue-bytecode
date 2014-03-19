package pointer.graph;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import pointer.statements.StatementRegistrar;
import util.PrettyPrinter;

import com.ibm.wala.shrikeCT.InvalidClassFileException;
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
     * Nodes for each type of exception that may be thrown
     */
    private final Map<TypeReference, LocalNode> exceptions = new LinkedHashMap<>();
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
                formals.add(registrar.getLocal(ir.getParameter(0), ir));
            }
        }
        for (int i = 1; i < ir.getNumberOfParameters(); i++) {
            formals.add(registrar.getLocal(ir.getParameter(i), ir));
        }

        TypeReference returnType = ir.getMethod().getReturnType();
        returnNode = (isVoid(returnType) || returnType.isPrimitiveType()) ? null : 
            // TODO this and below are the only places this is called outside of the Registrar
            new LocalNode(PrettyPrinter.parseMethod(ir.getMethod().getReference()) + " EXIT", returnType, false);  
        
        TypeReference[] exceptionTypes = null;
        try {
            exceptionTypes = ir.getMethod().getDeclaredExceptions();
        } catch (UnsupportedOperationException | InvalidClassFileException e) {
            throw new RuntimeException("Cannot find exception types for " + ir.getMethod().getSignature(), e);
        }
        for (TypeReference type : exceptionTypes) {
            String str = PrettyPrinter.parseMethod(ir.getMethod().getReference()) + " EXIT-"
                    + PrettyPrinter.parseType(type);
            LocalNode val = new LocalNode(str, type, false);
            exceptions.put(type, val);
        }
        if (!exceptions.containsKey(TypeReference.JavaLangRuntimeException)) {
            String str = PrettyPrinter.parseMethod(ir.getMethod().getReference()) + " EXIT-"
                    + PrettyPrinter.parseType(TypeReference.JavaLangRuntimeException);
            LocalNode val = new LocalNode(str, TypeReference.JavaLangRuntimeException, false);
            exceptions.put(TypeReference.JavaLangRuntimeException, val);
        }
        // TODO add exception node for errors if we are tracking errors
        
        name = PrettyPrinter.parseMethod(ir.getMethod().getReference());
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
    
    public Map<TypeReference, LocalNode> getExceptions() {
        return exceptions;
    }
    
    @Override
    public String toString() {
        return name;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((exceptions == null) ? 0 : exceptions.hashCode());
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
        if (exceptions == null) {
            if (other.exceptions != null)
                return false;
        } else if (!exceptions.equals(other.exceptions))
            return false;
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
