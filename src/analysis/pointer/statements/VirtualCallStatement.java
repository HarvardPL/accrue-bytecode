package analysis.pointer.statements;

import java.util.List;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
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
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInvokeInstruction;
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
     * Class hierarchy
     */
    private final IClassHierarchy cha;
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
     * @param receiver
     *            Receiver of the call
     * @param actuals
     *            Actual arguments to the call
     * @param resultNode
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param exceptionNode
     *            Node representing the exception thrown by this call (if any)
     * @param cha
     *            Class hierarchy
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @param rvFactory
     *            factory for managing the creation of reference variables for local variables and static fields
     */
    protected VirtualCallStatement(CallSiteReference callSite, MethodReference callee, ReferenceVariable receiver,
                                    List<ReferenceVariable> actuals, ReferenceVariable resultNode,
                                    ReferenceVariable exceptionNode, IClassHierarchy cha, IR ir,
                                    SSAInvokeInstruction i, WalaAnalysisUtil util, ReferenceVariableFactory rvFactory) {
        super(callSite, actuals, resultNode, exceptionNode, ir, i, util, rvFactory);
        assert receiver != null;
        assert callee != null;
        this.callee = callee;
        this.cha = cha;
        this.receiver = receiver;
    }

    // private static Set<ReferenceVariableReplica> lots = new HashSet<>();

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        ReferenceVariableReplica receiverRep = getReplica(context, receiver);

        if (DEBUG && g.getPointsToSet(receiverRep).isEmpty()) {
            System.err.println("RECEIVER: " + receiverRep + "\n\t"
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }

        boolean changed = false;
        Set<InstanceKey> s = g.getPointsToSet(receiverRep);
        // if (s.size() > 3000) {
        // if (!lots.contains(receiverRep)) {
        // System.err.println("Lots of receivers: " + s.size() + " for " + receiverRep + " of type "
        // + PrettyPrinter.typeString(receiverRep.getExpectedType()));
        // System.err.println("\tCALLING: " + PrettyPrinter.methodString(callee) + " from "
        // + PrettyPrinter.methodString(getCode().getMethod()));
        // lots.add(receiverRep);
        // }
        // List<InstanceKey> iks = new ArrayList<>(s);
        // Collections.sort(iks, ToStringComparator.<InstanceKey> instance());
        // for (InstanceKey ik : iks) {
        // System.err.println(ik + " HC: " + ik.hashCode());
        // for (InstanceKey ik2 : iks) {
        // if (ik.toString().equals(ik2.toString()) && ik != ik2) {
        // System.err.println(ik + " != " + ik2);
        // AllocationName<ContextStack<ClassWrapper>> an1 = (AllocationName<ContextStack<ClassWrapper>>)
        // ik;
        // AllocationName<ContextStack<ClassWrapper>> an2 = (AllocationName<ContextStack<ClassWrapper>>)
        // ik2;
        //
        // assert !an1.getAllocationSite().equals(an2.getAllocationSite());
        // assert an1.getContext().equals(an2.getContext());
        // int asn1 = an1.getAllocationSite().hashCode();
        // int asn2 = an2.getAllocationSite().hashCode();
        // int c1 = an1.getContext().hashCode();
        // int c2 = an2.getContext().hashCode();
        // // System.err.println("WTF");
        // }
        // }
        // }
        // throw new RuntimeException();
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
