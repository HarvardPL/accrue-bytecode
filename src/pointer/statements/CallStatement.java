package pointer.statements;

import java.util.List;
import java.util.Set;

import pointer.LocalNode;
import pointer.PointsToGraph;
import pointer.ReferenceVariableReplica;
import pointer.analyses.HeapAbstractionFactory;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.MethodReference;

/**
 * Points to statement for a call to a method
 */
public abstract class CallStatement implements PointsToStatement {

    protected static boolean processCall(CallSiteReference callSite, IR callerIR, Context callerContext,
            MethodReference callee, InstanceKey receiver, List<ReferenceVariableReplica> actuals,
            ReferenceVariableReplica resultNode, ReferenceVariableReplica exceptionNode, HeapAbstractionFactory haf,
            PointsToGraph g, StatementRegistrar registrar) {
        Context calleeContext = haf.merge(callSite, callerIR, receiver, callerContext);

        boolean changed = false;

        // Record the call
        changed |= g.addCall(callSite, callerContext, callee, calleeContext);
        
        MethodSummaryNodes summary = registrar.getSummaryNodes(callee);
        
        // add edge from "this" in the callee to the receiver
        // if this is a static call then the receiver will be null
        if (receiver != null) {
            ReferenceVariableReplica thisRep = new ReferenceVariableReplica(calleeContext, summary.getThisNode());
            changed |= g.addEdge(thisRep, receiver);
        }

        // add edges from the formal arguments to the actual arguments
        List<LocalNode> formals = summary.getFormals();
        for (int i = 0; i < actuals.size(); i++) {
            ReferenceVariableReplica actual = actuals.get(i);
            LocalNode formal = formals.get(i);
            if (actual == null && formal == null) {
                // Not a reference type
                continue;
            }
            assert(actual != null);
            assert(formal != null);

            ReferenceVariableReplica formalRep = new ReferenceVariableReplica(calleeContext, formal);

            Set<InstanceKey> actualHeapContexts = g.getPointsToSetFiltered(actual, formal.getExpectedType());
            changed |= g.addEdges(formalRep, actualHeapContexts);
        }

        // Add edge from the return formal to the result
        // If the result Node is null then either this is void return, there is
        // no assignment after the call, or the return type is not a reference
        if (resultNode != null) {
            assert (summary.getReturnNode() != null);
            ReferenceVariableReplica returnValueFormal = new ReferenceVariableReplica(calleeContext, summary.getReturnNode());
            
            changed |= g.addEdges(resultNode, g.getPointsToSetFiltered(returnValueFormal, resultNode.getExpectedType()));
        }
        
        // TODO exceptions
        
        return changed;
    }

}
