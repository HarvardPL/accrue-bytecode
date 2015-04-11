package analysis.pointer.statements;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.strings.Atom;

/**
 * Points-to statement for a call to a method
 */
public abstract class CallStatement extends PointsToStatement {

    /**
     * Call site
     */
    protected final CallSiteLabel callSite;
    /**
     * Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     */
    private final ReferenceVariable result;
    /**
     * Actual arguments to the call
     */
    private final List<ReferenceVariable> actuals;
    /**
     * Node representing the exception thrown by this call (if any)
     */
    private final ReferenceVariable exception;
    /**
     * Variable for the receiver
     */
    private final ReferenceVariable receiverRefVar;

    /**
     * Points-to statement for a special method invocation.
     *
     * @param callSite Method call site
     * @param caller caller method
     * @param result Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param actuals Actual arguments to the call
     * @param exception Node in the caller representing the exception thrown by this call (if any) also exceptions
     *            implicitly thrown by this statement
     */
    protected CallStatement(CallSiteReference callSite, IMethod caller, ReferenceVariable result,
                            List<ReferenceVariable> actuals, ReferenceVariable exception,
                            ReferenceVariable receiverRefVar) {
        super(caller);
        this.callSite = new CallSiteLabel(caller, callSite);
        this.actuals = actuals;
        this.result = result;
        this.exception = exception;
        this.receiverRefVar = receiverRefVar;
    }

    /**
     * Process a call for a particular receiver and resolved method
     *
     * @param callerContext Calling context for the caller
     * @param receiver Heap context for the receiver
     * @param callee Actual method being called
     * @param g points-to graph (may be modified)
     * @param haf abstraction factory used for creating new context from existing
     * @param calleeSummary summary nodes for formals and exits of the callee
     * @return true if the points-to graph has changed
     */
    protected final GraphDelta processCall(Context callerContext, InstanceKey receiver, IMethod callee,
                                           PointsToGraph g, HeapAbstractionFactory haf, MethodSummaryNodes calleeSummary) {
        assert calleeSummary != null;
        assert callee != null;
        assert calleeSummary != null;
        Context calleeContext = haf.merge(callSite, receiver, callerContext);
        GraphDelta changed = new GraphDelta(g);

        // Record the call in the call graph
        g.addCall(callSite.getReference(), getMethod(), callerContext, callee, calleeContext);

        // ////////////////// Return //////////////////

        // Add edge from the return formal to the result
        // If the result Node is null then either this is void return, there is
        // no assignment after the call, or the return type is not a reference
        if (result != null) {
            ReferenceVariableReplica resultRep = new ReferenceVariableReplica(callerContext, result, haf);
            ReferenceVariableReplica calleeReturn = new ReferenceVariableReplica(calleeContext,
                                                                                 calleeSummary.getReturn(),
                                                                                 haf);

            // Check whether the types match up appropriately
            assert checkTypes(resultRep, calleeReturn);

            // The assignee can point to anything the return summary node in the callee can point to
            GraphDelta retChange = g.copyEdges(calleeReturn, resultRep);
            changed = changed.combine(retChange);
        }

        // ////////////////// Receiver //////////////////

        // add edge from "this" in the callee to the receiver
        // if this is a static call then the receiver will be null
        if (!callee.isStatic()) {
            ReferenceVariableReplica thisRep = new ReferenceVariableReplica(calleeContext,
                                                                            calleeSummary.getFormal(0),
                                                                            haf);
            GraphDelta receiverChange = g.addEdge(thisRep, receiver);
            changed = changed.combine(receiverChange);
        }

        // ////////////////// Formal Arguments //////////////////

        // The first formal for a non-static call is the receiver which is handled specially above
        int firstFormal = callee.isStatic() ? 0 : 1;

        // add edges from local variables (formals) in the callee to the actual arguments
        for (int i = firstFormal; i < actuals.size(); i++) {
            ReferenceVariable actual = actuals.get(i);
            if (actual == null) {
                // Not a reference type or null actual
                continue;
            }
            ReferenceVariableReplica actualRep = new ReferenceVariableReplica(callerContext, actual, haf);

            ReferenceVariableReplica formalRep = new ReferenceVariableReplica(calleeContext,
                                                                              calleeSummary.getFormal(i),
                                                                              haf);

            // Check whether the types match up appropriately
            assert checkTypes(formalRep, actualRep);

            // Add edges from the points-to set for the actual argument to the formal argument
            GraphDelta d1 = g.copyEdges(actualRep, formalRep);
            changed = changed.combine(d1);
        }

        // ///////////////// Exceptions //////////////////

        ReferenceVariableReplica callerEx = new ReferenceVariableReplica(callerContext, exception, haf);
        ReferenceVariableReplica calleeEx = new ReferenceVariableReplica(calleeContext,
                                                                         calleeSummary.getException(),
                                                                         haf);

        // The exception in the caller can point to anything the summary node in the callee can point to
        GraphDelta exChange = g.copyEdges(calleeEx, callerEx);
        changed = changed.combine(exChange);

        if (!AnalysisUtil.disableObjectClone && callee.getReference().equals(CLONE)) {
            // Do not actually "call" Object.clone() and array clone, just record the effects here
            GraphDelta objCloneChange = processObjectOrArrayClone(callerContext, receiver, calleeContext, g, haf);
            changed = changed.combine(objCloneChange);
        }
        return changed;
    }

    private final static Atom cloneAtom = Atom.findOrCreateUnicodeAtom("clone");
    private final static Descriptor cloneDesc = Descriptor.findOrCreateUTF8("()Ljava/lang/Object;");
    public final static MethodReference CLONE = MethodReference.findOrCreate(TypeReference.JavaLangObject,
                                                                             cloneAtom,
                                                                             cloneDesc);
    public static final ConcurrentHashMap<IClass, AllocSiteNode> cloneAllocations = new ConcurrentHashMap<>();

    /**
     * Process a call to Object.clone()
     * <p>
     * XXX handle {@link CloneNotSupportedException}
     * <p>
     * XXX make this its own statement separate from CallStatement, could be the single statment inside Object.clone()
     *
     * @param callerContext context for the caller
     * @param receiver receiver heap context
     * @param calleeContext context for the callee
     * @param g points-to graph
     * @param haf Heap abstraction factory
     * @param calleeSummary summary nodes for the call to clone
     * @return Any changes made to the points-to graph
     */
    private final GraphDelta processObjectOrArrayClone(Context callerContext, InstanceKey receiver,
                                                       Context calleeContext, PointsToGraph g,
                                                       HeapAbstractionFactory haf) {
        GraphDelta changed = new GraphDelta(g);
        if (result == null) {
            // Object.clone() has no effect unless we are assigning the result to something
            return changed;
        }

        if (receiverRefVar.isSingleton()) {
            // This is a clone of a singleton location, just add an edge directly from the result to the receiver
            return g.addEdge(new ReferenceVariableReplica(callerContext, result, haf), receiver);
        }

        assert result != null;


        // Allocate a new object to hold the results of the clone
        String name = "clone-" + PrettyPrinter.typeString(receiver.getConcreteType());
        AllocSiteNode n = cloneAllocations.get(receiver.getConcreteType());
        if (n == null) {
            // pass null in as the result, there may be multiple recievers for a single call site each of which gets an allocation
            n = AllocSiteNodeFactory.createGenerated(name, receiver.getConcreteType(), getMethod(), null, false);
            AllocSiteNode existing = cloneAllocations.putIfAbsent(receiver.getConcreteType(), n);
            if (existing != null) {
                n = existing;
            }
        }

        InstanceKey newHeapContext = haf.record(n, calleeContext);
        assert newHeapContext != null;

        // Assign the new object to the return value of the callee
        ReferenceVariableReplica r = new ReferenceVariableReplica(callerContext, result, haf);
        GraphDelta resChanged = g.addEdge(r, newHeapContext);
        changed = changed.combine(resChanged);

        if (receiver.getConcreteType().isArrayClass()) {
            // Copy the CONTENTS field from the old array to the new one
            TypeReference baseType = receiver.getConcreteType().getReference().getArrayElementType();
            if (baseType.isPrimitiveType()) {
                return changed;
            }
            ObjectField contents = new ObjectField(receiver,
                                                   PointsToGraph.ARRAY_CONTENTS,
                                                   AnalysisUtil.getClassHierarchy().lookupClass(baseType));
            ObjectField newContents = new ObjectField(newHeapContext,
                                                      PointsToGraph.ARRAY_CONTENTS,
                                                      AnalysisUtil.getClassHierarchy().lookupClass(baseType));
            GraphDelta contentsChange = g.copyEdges(contents, newContents);
            changed = changed.combine(contentsChange);
        }
        else {
            // Copy edges from the fields from the receiver to the fields of the new object
            for (IField f : receiver.getConcreteType().getAllInstanceFields()) {
                if (f.getFieldTypeReference().isPrimitiveType()) {
                    // Primitive fields get copied not aliased
                    continue;
                }
                assert AnalysisUtil.getClassHierarchy().lookupClass(f.getFieldTypeReference()) != null : "NO CLASS found for "
                        + f.getFieldTypeReference() + " in " + f;
                ObjectField of = new ObjectField(receiver, f);
                ObjectField newOF = new ObjectField(newHeapContext, f);
                GraphDelta fieldCopyChange = g.copyEdges(of, newOF);
                changed = changed.combine(fieldCopyChange);
            }
        }

        return changed;
    }

    /**
     * Result of the call if any, null if void or primitive return or if the return result is not assigned
     *
     * @return return result node (in the caller)
     */
    protected final ReferenceVariable getResult() {
        return result;
    }

    /**
     * Actual arguments to the call (if the type is primitive or it is a null literal then the entry will be null)
     *
     * @return list of actual parameters
     */
    protected final List<ReferenceVariable> getActuals() {
        return actuals;
    }

    /**
     * Reference variable for any exceptions thrown by this call (including a NullPointerException due to the receiver
     * being null)
     *
     * @return exception reference variable
     */
    public ReferenceVariable getException() {
        return exception;
    }

    /**
     * (Unresolved) Method being called. The actual method depends on the run-time type of the receiver, which is
     * approximated by the pointer analysis.
     *
     * @return callee
     */
    public MethodReference getCallee() {
        return callSite.getCallee();
    }

    /**
     * Replace the variable for an actual argument with the given variable
     *
     * @param argNum index of the argument to replace
     * @param newVariable new reference variable
     */
    protected void replaceActual(int argNum, ReferenceVariable newVariable) {
        actuals.set(argNum, newVariable);
    }
}
