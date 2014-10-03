package analysis.pointer.statements;

import util.print.PrettyPrinter;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.types.MethodReference;

/**
 * Description of a (statically) unique method call site
 */
public class CallSiteProgramPoint extends ProgramPoint {
    /**
     * Call site in the caller
     */
    private final CallSiteReference callSite;
    private final IMethod caller;

    public CallSiteProgramPoint(IMethod caller, CallSiteReference callSite) {
        super(caller, PrettyPrinter.methodString(caller) + "@" + callSite.getProgramCounter());
        this.callSite = callSite;
        this.caller = caller;
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
     * Static call site, is not be unique without the corresponding resilved caller
     *
     * @return program counter and method target
     */
    public CallSiteReference getReference() {
        return callSite;
    }

    public IMethod getCaller() {
        return caller;
    }
}
