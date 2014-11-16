package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.MethodReference;

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
                            List<ReferenceVariable> actuals, ReferenceVariable exception) {
        super(caller);
        this.callSite = new CallSiteLabel(caller, callSite);
        this.actuals = actuals;
        this.result = result;
        this.exception = exception;
    }

    /**
     * Process a call for a particular receiver and resolved method
     * 
     * @param callerContext Calling context for the caller
     * @param receiver Heap context for the receiver
     * @param args heap contexts for the arguments
     * @param callee Actual method being called
     * @param g points-to graph (may be modified)
     * @param haf abstraction factory used for creating new context from existing
     * @param calleeSummary summary nodes for formals and exits of the callee
     * @return true if the points-to graph has changed
     */
    protected final GraphDelta processCall(Context callerContext, InstanceKey receiver, List<InstanceKey> args,
                                           IMethod callee, PointsToGraph g, HeapAbstractionFactory haf,
                                           MethodSummaryNodes calleeSummary) {
        assert calleeSummary != null;
        assert callee != null;
        assert calleeSummary != null;
        Context calleeContext = haf.merge(callSite, receiver, args, callerContext);
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

    /**
     * Iterator over the points-to sets of a list of method arguments
     */
    protected class ArgumentIterator implements Iterator<List<InstanceKey>> {

        /**
         * Variable and context for all method arguments (including the receiver which should be first if there is one)
         */
        private final List<ReferenceVariableReplica> args;
        /**
         * Points-to graph
         */
        private final PointsToGraph g;
        /**
         * Iterators for the points-to sets for the arguments, these will be "reset" when exhausted
         */
        private final List<Iterator<InstanceKey>> pointsToIters;
        /**
         * Statement and context we need this iterator for (should be a call statement)
         */
        private final StmtAndContext originator;
        /**
         * The, already computed, next element in the iterator, could be null if no element has been computed yet.
         */
        private List<InstanceKey> next;
        /**
         * The last element computed for this iterator, could be the same as "next"
         */
        private List<InstanceKey> lastComputed;
        /**
         * if there is a delta then this is the index for the iterator that came from the delta in the iterator list
         */
        private int currentDelta;
        /**
         * Delta change-set for a points-to graph, could be null
         */
        private final GraphDelta delta;

        /**
         * Iterator that exhaustively covers all combinations of elements of the points-to sets of the <code>args</code>
         *
         * @param args Variable and context for all method arguments (including the receiver which should be first if
         *            there is one)
         * @param originator Statement and context we need this iterator for (should be a call statement)
         * @param g Points-to graph
         * @param delta change-set for a points-to graph, could be null
         */
        public ArgumentIterator(List<ReferenceVariableReplica> args, StmtAndContext originator, PointsToGraph g,
                                GraphDelta delta) {
            assert args != null;
            assert originator != null;
            assert g != null;

            this.args = args;
            this.g = g;
            this.delta = delta;
            this.originator = originator;

            this.pointsToIters = new ArrayList<>(args.size());

            for (ReferenceVariableReplica arg : args) {
                // Initialize the argument iterators and the arguments from the points to graph
                pointsToIters.add(g.pointsToIterator(arg, originator));
            }

            if (delta != null) {
                boolean isNonEmptyDelta = replaceFirstNonEmptyDelta(args.size() - 1);
                if (!isNonEmptyDelta) {
                    // There were no non-empty deltas so this iterator should be empty
                    System.err.println("All deltas are empty for arguments added by " + this.originator);
                    next = null;
                    for (int i = 0; i < pointsToIters.size(); i++) {
                        pointsToIters.set(i, Collections.<InstanceKey> emptyIterator());
                    }
                }
            }

            // Initialize the "next" element to contain the first of each iterator
            next = getNextFromEachIterator();
            this.lastComputed = next;
        }

        /**
         * Loop through the iterators constructing a list with the next element from each
         *
         * @return list containing the next element of each iterator or null if there are no more elements in a
         *         particular iterator
         */
        private List<InstanceKey> getNextFromEachIterator() {
            ArrayList<InstanceKey> init = new ArrayList<>(pointsToIters.size());
            // Initialize the initial list of argument instance keys
            for (Iterator<InstanceKey> iter : pointsToIters) {
                if (iter.hasNext()) {
                    init.add(iter.next());
                }
                else {
                    System.err.println("Empty points-to iterator for argument in " + originator);
                    // The iterator has no elements set the element to null
                    init.add(null);
                }
            }
            return init;
        }

        /**
         * Replace the first non-empty delta with an index less that i
         *
         * @param i starting index
         * @return true if any delta from i down to zero was non-empty
         */
        private boolean replaceFirstNonEmptyDelta(int i) {
                currentDelta = i - 1;
                Iterator<InstanceKey> deltaIter = null;
                do {
                    // If any delta is non-empty, set it in the iterator array, otherwise there is nothing to do
                    deltaIter = delta.pointsToIterator(args.get(currentDelta));
                    pointsToIters.set(currentDelta, deltaIter);
                } while (currentDelta-- >= 0 && !deltaIter.hasNext());

            return currentDelta < 0;
        }

        @Override
        public boolean hasNext() {
            if (next != null) {
                // the next element has already been computed
                return true;
            }

            // Start at the end
            int currentIter = args.size() - 1;
            while (!pointsToIters.get(currentIter).hasNext() && currentIter >= 0) {
                // The iterator at currentIter has been completed, reset it and move to the previous
                pointsToIters.set(currentIter, g.pointsToIterator(args.get(currentIter), originator));
                currentIter--;
            }

            if (currentIter < 0) {
                // All the iterators were exhausted and another element was asked for

                if (delta == null) {
                    // There is no delta so this is the actual end of the iterator
                    return false;
                }

                if (currentDelta == 0) {
                    // Already gone through all deltas
                    return false;
                }

                // Put the full iterator in the list to replace the delta
                pointsToIters.set(currentDelta, g.pointsToIterator(args.get(currentDelta), originator));

                // Find the next non-empty delta if any
                boolean isNonEmptyDelta = replaceFirstNonEmptyDelta(currentDelta - 1);
                if (!isNonEmptyDelta) {
                    // There are no non-empty delta sets left
                    return false;
                }

                // Start over from the beginning with the new delta
                next = getNextFromEachIterator();
                lastComputed = next;
                return true;
            }

            next = new ArrayList<>(lastComputed);
            next.set(currentIter, pointsToIters.get(currentIter).next());
            lastComputed = next;
            return true;
        }

        @Override
        public List<InstanceKey> next() {
            if (hasNext()) {
                List<InstanceKey> n = next;
                next = null;
                return n;
            }

            // All the iterators were exhausted and another element was asked for
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Cannot remove from an ArgumentIterator. Points-to sets only grow.");
        }

    }
}
