package analysis.pointer.statements;

import java.util.List;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.MethodReference;

/**
 * Points-to statement for a call to a virtual method (either a class or interface method).
 */
public class VirtualCallStatement extends CallStatement {

    /**
     * Called method
     */
    private final MethodReference callee;
    /**
     * Reference variable for the receiver of the call
     */
    private final ReferenceVariable receiver;

    /**
     * Points-to statement for a virtual method invocation.
     * 
     * @param callSite
     *            Method call site
     * @param callee
     *            Method being called
     * @param caller
     *            caller method
     * @param result
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param receiver
     *            Receiver of the call
     * @param actuals
     *            Actual arguments to the call
     * @param exception
     *            Node representing the exception thrown by this call (if any)
     * @param rvFactory
     *            factory for managing the creation of reference variables for local variables and static fields
     */
    protected VirtualCallStatement(CallSiteReference callSite, MethodReference callee, IMethod caller,
                                    ReferenceVariable result, ReferenceVariable receiver,
                                    List<ReferenceVariable> actuals, ReferenceVariable exception) {
        super(callSite, actuals, result, exception, ir, i, rvFactory);
        assert receiver != null;
        assert callee != null;
        this.callee = callee;
        this.receiver = receiver;
    }

    // private static Map<ReferenceVariableReplica, Integer> lots = new HashMap<>();

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
        ReferenceVariableReplica receiverRep = getReplica(context, receiver);

        if (DEBUG && g.getPointsToSet(receiverRep).isEmpty()) {
            System.err.println("RECEIVER: " + receiverRep + "\n\t"
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }

        boolean changed = false;
        Set<InstanceKey> s = g.getPointsToSet(receiverRep);
        // if (s.size() > 5000) {
        // Integer i = lots.get(receiverRep);
        // if (i == null || s.size() > (i + 1000)) {
        // lots.put(receiverRep, s.size());
        // System.err.print("Lots of receivers: " + s.size() + " for " + receiverRep + " of type "
        // + PrettyPrinter.typeString(receiverRep.getExpectedType()));
        // System.err.println("\tCALLING: " + PrettyPrinter.methodString(callee) + " from "
        // + PrettyPrinter.methodString(getCode().getMethod()) + "\n");
        // // CFGWriter.writeToFile(getCode());
        // System.err.println("\t" + "POINTS-TO SET");
        // for (InstanceKey ik : s) {
        // System.err.println("\t\t" + ik + " HashCode: " + ik.hashCode());
        // }
        // }
        // }
        for (InstanceKey recHeapContext : s) {
            // find the callee.
            // The receiver is recHeapContext, and we want to find a method that matches selector
            // callee.getSelector() in class recHeapContext.getConcreteType() or
            // a superclass.
            IMethod resolvedCallee = cha.resolveMethod(recHeapContext.getConcreteType(), callee.getSelector());
            if (resolvedCallee == null) {
                // XXX Try the type of the reference variable instead
                // This is probably a variable created for the return of a native method, then cast down
                resolvedCallee = cha.resolveMethod(cha.lookupClass(receiverRep.getExpectedType()), callee.getSelector());
            }

            if (resolvedCallee != null && resolvedCallee.isAbstract()) {
                // Abstract method due to a native method that returns an abstract type or interface
                // TODO Handle abstract methods in a smarter way
                System.err.println("Abstract method " + PrettyPrinter.methodString(resolvedCallee));
                continue;
            }

            // If we wanted to be very robust, check to make sure that
            // resolvedCallee overrides
            // the IMethod returned by ch.resolveMethod(callee).
            changed |= processCall(context, recHeapContext, resolvedCallee, g, registrar, haf);
        }
        return changed;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (getResultNode() != null) {
            s.append(getResultNode().toString() + " = ");
        }
        s.append("invokevirtual " + PrettyPrinter.methodString(getCallSiteLabel().getCallee()));

        return s.toString();
    }
}
