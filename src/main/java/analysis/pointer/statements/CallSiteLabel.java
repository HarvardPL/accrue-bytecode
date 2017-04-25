package analysis.pointer.statements;

import util.print.PrettyPrinter;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.types.MethodReference;

/**
 * Description of a (statically) unique method call site
 */
public class CallSiteLabel {
    /**
     * Calling method
     */
    private final IMethod caller;
    /**
     * Call site in the caller
     */
    private final CallSiteReference callSite;
    
    /**
     * compute once and store
     */
    private final int memoizedHashCode;

    /**
     * Description of a method call site
     * 
     * @param caller
     *            caller
     * @param callSite
     *            call site in the caller
     */
    public CallSiteLabel(IMethod caller, CallSiteReference callSite) {
        this.caller = caller;
        this.callSite = callSite;
        this.memoizedHashCode = computeHashCode();
    }

    /**
     * Static (unresolved) method callee
     * 
     * @return method callee
     */
    public MethodReference getCallee() {
        return callSite.getDeclaredTarget();
    }

    /**
     * Is this a call to a static method
     * 
     * @return true if this call site is for a static method call
     */
    public boolean isStatic() {
        return callSite.isStatic();
    }
    
    /**
     * Compute once and store
     * 
     * @return hash code
     */
    private int computeHashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((callSite == null) ? 0 : callSite.hashCode());
        result = prime * result + ((caller == null) ? 0 : caller.hashCode());
        return result;
    }

    @Override
    public int hashCode() {
        return memoizedHashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        CallSiteLabel other = (CallSiteLabel) obj;
        if (callSite == null) {
            if (other.callSite != null)
                return false;
        } else if (!callSite.equals(other.callSite))
            return false;
        if (caller == null) {
            if (other.caller != null)
                return false;
        } else if (!caller.equals(other.caller))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return PrettyPrinter.methodString(caller) + "@" + callSite.getProgramCounter();
    }

    /**
     * Static call site, is not be unique without the corresponding resilved caller
     * 
     * @return program counter and method target
     */
    public CallSiteReference getReference() {
        return callSite;
    }
}
