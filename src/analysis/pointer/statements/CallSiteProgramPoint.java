package analysis.pointer.statements;

import java.util.Map;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * Description of a (statically) unique method call site
 */
public class CallSiteProgramPoint extends ProgramPoint {
    /**
     * Call site in the caller
     */
    private final CallSiteReference callSite;
    private final IMethod caller;
    private final Map<TypeReference, ProgramPoint> exceptions;

    /**
     * Create a program point for the normal exit from a method call, in the caller
     *
     * @param caller method containing the call
     * @param callSite call site
     * @param exceptions program point per exception type in the callee
     */
    public CallSiteProgramPoint(IMethod caller, CallSiteReference callSite,
                                Map<TypeReference, ProgramPoint> exceptions) {
        super(caller, PrettyPrinter.methodString(caller) + "@" + callSite.getProgramCounter());
        this.callSite = callSite;
        this.caller = caller;
        this.exceptions = exceptions;
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
     * Static call site, is not unique without the corresponding resolved caller
     *
     * @return program counter and method target
     */
    public CallSiteReference getReference() {
        return callSite;
    }

    /**
     * Get the method containing the call site
     *
     * @return method containing the call site
     */
    public IMethod getCaller() {
        return caller;
    }

    /**
     * Get the program point for exceptional exit of this call in the caller, given the type of exception being thrown
     * by the callee
     *
     * @param t type of the exception being thrown
     * @return Program point in the caller
     */
    public ProgramPoint getExceptionExit(TypeReference t) {
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
        IClass c = cha.lookupClass(t);
        ProgramPoint pp = null;
        IClass classForPP = null;
        for (TypeReference exitType : exceptions.keySet()) {
            IClass exitClass = cha.lookupClass(exitType);
            if (TypeRepository.isAssignableFrom(exitClass, c)) {
                // The exit type is a subtype of the type we are checking so the PP is possible
                if (classForPP == null || TypeRepository.isAssignableFrom(classForPP, exitClass)) {
                    // Either we had no valid PP yet or this PP is for a more specific (and still valid) exception type
                    pp = exceptions.get(exitType);
                    classForPP = exitClass;
                }
            }
        }

        assert pp != null : "Invalid exception type " + t;
        return pp;
    }
}
