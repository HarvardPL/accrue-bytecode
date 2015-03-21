package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

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
     * Points-to statement for a virtual method invocation.
     *
     * @param callerPP caller program point
     * @param callee Method being called
     * @param result Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param receiver Receiver of the call
     * @param actuals Actual arguments to the call
     * @param exception Node representing the exception thrown by this call (if any)
     */
    protected VirtualCallStatement(CallSiteProgramPoint callerPP, MethodReference callee, ReferenceVariable result,
                                   ReferenceVariable receiver, List<ReferenceVariable> actuals,
                                   ReferenceVariable exception) {
        super(callerPP, result, actuals, exception);

        this.callee = callee;
        this.receiver = receiver;
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        ReferenceVariableReplica receiverRep = new ReferenceVariableReplica(context, this.receiver, haf);

        GraphDelta changed = new GraphDelta(g);

        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());

        Iterator<InstanceKeyRecency> iter = delta == null ? g.pointsToIterator(receiverRep, pre, originator)
                : delta.pointsToIterator(receiverRep, pre, originator);

        boolean nonNullTargetSeen = false;

        while (iter.hasNext()) {
            InstanceKeyRecency recHeapContext = iter.next();
            if (g.isNullInstanceKey(recHeapContext)) {
                // The receiver points to null that is not a "real" call so there is nothing to process
                continue;
            }
            nonNullTargetSeen = true;
            // The receiver is recHeapContext, and we want to find a method that matches selector callee.getSelector()
            // in class recHeapContext.getConcreteType() or a superclass.
            IMethod resolvedCallee = this.resolveMethod(recHeapContext.getConcreteType(), receiverRep.getExpectedType());

            if (resolvedCallee != null && resolvedCallee.isAbstract()) {
                // Abstract method due to a native method that returns an abstract type or interface
                // TODO Handle abstract methods in a smarter way
                System.err.println("Abstract method " + PrettyPrinter.methodString(resolvedCallee));
                continue;
            }

            // If we wanted to be very robust, check to make sure that
            // resolvedCallee overrides the IMethod returned by ch.resolveMethod(callee).
            changed = changed.combine(this.processCall(context,
                                                       recHeapContext,
                                                       resolvedCallee,
                                                       g,
                                                       haf,
                                                       registrar.findOrCreateMethodSummary(resolvedCallee)));

        }

        if (!nonNullTargetSeen) {
            // This could be an empty call site
            int callSite = g.lookupCallSiteReplicaDictionary(this.programPoint().getReplica(context));
            g.ppReach.getApproximateCallSitesAndFieldAssigns().addEmptyCallSite(callSite);
        }

        return changed;
    }

    protected IMethod resolveMethod(IClass receiverConcreteType, TypeReference receiverExpectedType) {
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
        // XXXX possible point of contention...!@!
        synchronized (cha) {
            IMethod resolvedCallee = cha.resolveMethod(receiverConcreteType, this.callee.getSelector());
            if (resolvedCallee == null) {
                // XXX Try the type of the reference variable instead
                // This is probably a variable created for the return of a native method, then cast down
                IClass receiverClass = cha.lookupClass(receiverExpectedType);
                assert receiverClass != null;
                if (PointsToAnalysis.outputLevel >= 1) {
                    System.err.println("Could not resolve " + receiverConcreteType + " " + this.callee.getSelector());
                    System.err.println("\ttrying reference variable type " + receiverClass);
                }
                resolvedCallee = cha.resolveMethod(receiverClass, this.callee.getSelector());
            }
            return resolvedCallee;
        }
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (this.getResult() != null) {
            s.append(this.getResult().toString() + " = ");
        }
        s.append("invokevirtual " + PrettyPrinter.methodString(this.callee));

        s.append(" -- ");
        s.append(this.receiver);
        s.append(".");
        s.append(this.callee.getName());
        s.append("(");
        List<ReferenceVariable> actuals = this.getActuals();
        if (this.getActuals().size() > 1) {
            s.append(actuals.get(1));
        }
        for (int j = 2; j < actuals.size(); j++) {
            s.append(", ");
            s.append(actuals.get(j));
        }
        s.append(")");

        return s.toString();
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber <= this.getActuals().size() && useNumber >= 0;
        if (useNumber == 0) {
            this.receiver = newVariable;
            return;
        }
        this.replaceActual(useNumber - 1, newVariable);
    }

    /**
     * Variable for receiver followed by variables for arguments in order
     *
     * {@inheritDoc}
     */
    @Override
    public List<ReferenceVariable> getUses() {
        List<ReferenceVariable> uses = new ArrayList<>(this.getActuals().size() + 1);
        uses.add(this.receiver);
        uses.addAll(this.getActuals());
        return uses;
    }
}
