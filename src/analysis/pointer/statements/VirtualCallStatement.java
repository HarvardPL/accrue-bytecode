package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory;
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
    private ReferenceVariable receiver;
    /**
     * Factory used to create callee summary nodes
     */
    private final ReferenceVariableFactory rvFactory;

    /**
     * Points-to statement for a virtual method invocation.
     * 
     * @param callSite
     *            Method call site
     * @param caller
     *            caller method
     * @param callee
     *            Method being called
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
    protected VirtualCallStatement(CallSiteReference callSite, IMethod caller, MethodReference callee,
                                    ReferenceVariable result, ReferenceVariable receiver,
                                    List<ReferenceVariable> actuals, ReferenceVariable exception,
                                    ReferenceVariableFactory rvFactory) {
        super(callSite, caller, result, actuals, exception);

        this.callee = callee;
        this.receiver = receiver;
        this.rvFactory = rvFactory;
    }

    // private static Map<ReferenceVariableReplica, Integer> lots = new HashMap<>();

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
        ReferenceVariableReplica receiverRep = new ReferenceVariableReplica(context, receiver);

        Set<InstanceKey> s = g.getPointsToSet(receiverRep);
        assert checkForNonEmpty(s, receiverRep, "VIRTUAL RECEIVER");

        boolean changed = false;
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
                if (PointsToAnalysis.outputLevel >= 1) {
                    System.err.println("Could not resolve " + recHeapContext.getConcreteType() + " "
                                                    + callee.getSelector());
                    System.err.println("\ttrying reference variable type "
                                                    + cha.lookupClass(receiverRep.getExpectedType()));
                }
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
            changed |= processCall(context, recHeapContext, resolvedCallee, g, haf,
                                            registrar.findOrCreateMethodSummary(resolvedCallee, rvFactory));
        }
        return changed;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (getResult() != null) {
            s.append(getResult().toString() + " = ");
        }
        s.append("invokevirtual " + PrettyPrinter.methodString(callee));

        return s.toString();
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber <= getActuals().size() && useNumber >= 0;
        if (useNumber == 0) {
            receiver = newVariable;
            return;
        }
        replaceActual(useNumber - 1, newVariable);
    }

    /**
     * Variable for receiver followed by variables for arguments in order
     * 
     * {@inheritDoc}
     */
    @Override
    public List<ReferenceVariable> getUses() {
        List<ReferenceVariable> uses = new ArrayList<>(getActuals().size() + 1);
        uses.add(receiver);
        uses.addAll(getActuals());
        return uses;
    }

    @Override
    public ReferenceVariable getDef() {
        return getResult();
    }
}
