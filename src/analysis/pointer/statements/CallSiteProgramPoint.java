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
    private final ProgramPoint exceptionExit;
    private final boolean isClinit;
    private final IMethod clinit;
    private final ProgramPoint normalExit;

    /**
     * Create a program point for the normal exit from a method call, in the caller
     *
     * @param caller method containing the call
     * @param callSite call site
     * @param exceptionExit program point for exceptional exit the caller
     */
    public CallSiteProgramPoint(IMethod caller, CallSiteReference callSite, ProgramPoint exceptionExit,
                                ProgramPoint normalExit) {
        super(caller, PrettyPrinter.methodString(callSite.getDeclaredTarget()) + " from "
                + PrettyPrinter.methodString(caller) + "@"
                + callSite.getProgramCounter());
        this.callSite = callSite;
        this.caller = caller;
        this.exceptionExit = exceptionExit;
        this.normalExit = normalExit;
        this.isClinit = false;
        this.clinit = null;
    }

    private CallSiteProgramPoint(IMethod clinit, IMethod caller, ProgramPoint exceptionExit) {
        super(caller, "CLINIT " + PrettyPrinter.methodString(clinit));
        this.isClinit = true;
        this.callSite = null;
        this.exceptionExit = exceptionExit;
        this.normalExit = this;
        this.clinit = clinit;
        this.caller = caller;
    }

    /**
     * Create a program point the normal exit of a static initializer
     *
     * @param caller method "calling" the static initializer
     * @param init initializer being called
     * @param exceptionExit program point for exceptional exit the caller
     */
    public static CallSiteProgramPoint createClassInit(IMethod init, IMethod caller, ProgramPoint exceptionExit) {
        return new CallSiteProgramPoint(init, caller, exceptionExit);
    }

    /**
     * Static (unresolved) method callee
     *
     * @return method callee
     */
    public MethodReference getCallee() {
        if (isClinit) {
            return clinit.getReference();
        }
        return callSite.getDeclaredTarget();
    }

    /**
     * Is this a call to a static method
     *
     * @return true if this call site is for a static method call
     */
    public boolean isStatic() {
        if (isClinit) {
            return true;
        }
        return callSite.isStatic();
    }

    /**
     * Static call site, is not unique without the corresponding resolved caller
     *
     * @return program counter and method target
     */
    public CallSiteReference getReference() {
        assert !isClinit : "No call site for class initializer program points.";
        return callSite;
    }

    /**
     *
     * @return true if this is the program point for the call to a class initialization method
     */
    public boolean isClinit() {
        return this.isClinit;
    }

    /**
     * If this is the program point for a class initializer then return it. Do not call this without checking isClinit()
     * first.
     *
     * @return the class initializer
     */
    public IMethod getClinit() {
        assert this.isClinit;
        return this.clinit;
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
     * Get the program point for exceptional exit of this call in the caller
     *
     * @return Program point in the caller
     */
    public ProgramPoint getExceptionExit() {
        // TODO handle exits per exception type
        //        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
        //        IClass c = cha.lookupClass(t);
        //        ProgramPoint pp = null;
        //        IClass classForPP = null;
        //        for (TypeReference exitType : exceptions.keySet()) {
        //            IClass exitClass = cha.lookupClass(exitType);
        //            if (TypeRepository.isAssignableFrom(exitClass, c)) {
        //                // The exit type is a subtype of the type we are checking so the PP is possible
        //                if (classForPP == null || TypeRepository.isAssignableFrom(classForPP, exitClass)) {
        //                    // Either we had no valid PP yet or this PP is for a more specific (and still valid) exception type
        //                    pp = exceptions.get(exitType);
        //                    classForPP = exitClass;
        //                }
        //            }
        //        }
        //
        //        assert pp != null : "Invalid exception type " + t;
        return exceptionExit;
    }

    /**
     * Get the program point for normal exit of this call in the caller
     *
     * @return Program point in the caller
     */
    public ProgramPoint getNormalExit() {
        return normalExit;
    }
}
