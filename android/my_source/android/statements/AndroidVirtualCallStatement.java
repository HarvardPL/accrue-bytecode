package android.statements;

import java.util.Iterator;
import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.VirtualCallStatement;
import android.AndroidConstants;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.MethodReference;

/**
 * Virtual call to a method in an android application
 */
public class AndroidVirtualCallStatement extends VirtualCallStatement {

    /**
     * Points-to statement for a virtual method invocation.
     *
     * @param callSite Method call site
     * @param caller caller method
     * @param callee Method being called
     * @param result Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param receiver Receiver of the call
     * @param actuals Actual arguments to the call
     * @param callerException Node representing the exception thrown by this call (if any)
     * @param rvFactory factory used to find callee summary nodes
     * @return statement to be processed during pointer analysis
     */
    public AndroidVirtualCallStatement(CallSiteReference callSite, IMethod caller, MethodReference callee,
                                       ReferenceVariable result, ReferenceVariable receiver,
                                       List<ReferenceVariable> actuals, ReferenceVariable callerException,
                                       ReferenceVariableFactory rvFactory) {
        super(callSite, caller, callee, result, receiver, actuals, callerException, rvFactory);
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext sac) {
        ReferenceVariableReplica receiverRep = new ReferenceVariableReplica(context, this.getUses().get(0), haf);

        // Find if there are any targets that are interesting methods and handle those specially
        Iterator<InstanceKey> iter = delta == null ? g.pointsToIterator(receiverRep, sac)
                : delta.pointsToIterator(receiverRep);
        while (iter.hasNext()) {
            InstanceKey recHeapContext = iter.next();
            IMethod resolvedCallee = this.resolveMethod(recHeapContext.getConcreteType(), receiverRep.getExpectedType());

            if (AndroidConstants.INTERESTING_METHODS.contains(resolvedCallee)) {
                InterestingMethodProcessor.process(resolvedCallee, context, haf, g, registrar);
            }
        }
        return super.process(context, haf, g, delta, registrar, sac);
    }
}
