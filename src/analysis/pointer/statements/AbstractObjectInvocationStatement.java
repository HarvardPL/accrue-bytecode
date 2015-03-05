package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

public abstract class AbstractObjectInvocationStatement<MethodType> extends
 PointsToStatement {

    protected final MethodType method;
    protected final CallSiteLabel callSite;
    protected final List<ReferenceVariable> actuals;
    protected final ReferenceVariable result;
    protected final ReferenceVariable exception;
    protected ReferenceVariable receiver;

    public AbstractObjectInvocationStatement(MethodType method, CallSiteReference callSite, IMethod caller,
                                             ReferenceVariable result, ReferenceVariable receiver,
                                             List<ReferenceVariable> actuals, ReferenceVariable exception) {
        super(caller);
        this.method = method;
        this.callSite = new CallSiteLabel(caller, callSite);
        this.result = result;
        this.receiver = receiver;
        this.actuals = actuals;
        this.exception = exception;
    }

    @Override
    public final GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                        GraphDelta delta, StatementRegistrar registrar,
                                        StmtAndContext originator) {
        List<ReferenceVariableReplica> actualsReplicas = new LinkedList<>();
        for(ReferenceVariable actual : this.actuals) {
            actualsReplicas.add(new ReferenceVariableReplica(context, actual, haf));
        }
        ReferenceVariableReplica receiverReplica = this.receiver == null ? null
                : new ReferenceVariableReplica(context, this.receiver, haf);
        ReferenceVariableReplica resultReplica = this.result == null ? null : new ReferenceVariableReplica(context,
                                                                                                           this.result,
                                                                                                           haf);
        ReferenceVariableReplica exceptionReplica = new ReferenceVariableReplica(context, this.exception, haf);

        PointsToIterable pti = delta == null ? g : delta;

        Iterable<InstanceKey> receiverIKs = receiverReplica == null ? null : pti.pointsToIterable(receiverReplica, originator);
        Iterable<InstanceKey> resultIKs = receiverReplica == null ? null : pti.pointsToIterable(resultReplica, originator);
        Iterable<InstanceKey> exceptionIKs = pti.pointsToIterable(exceptionReplica, originator);

        List<Iterable<InstanceKey>> actualsIKs = new ArrayList<>();
        for(ReferenceVariableReplica ar : actualsReplicas) {
            actualsIKs.add(ar == null ? null : pti.pointsToIterable(ar, originator));
        }

        GraphDelta changed = new GraphDelta(g);

        return processMethod(context, actualsIKs, receiverIKs, resultIKs, resultReplica, exceptionIKs, g, changed, haf);
    }

    protected abstract GraphDelta processMethod(Context context, List<Iterable<InstanceKey>> actualsIKs,
                                                Iterable<InstanceKey> receiverIKs, Iterable<InstanceKey> resultIKs,
                                                ReferenceVariableReplica resultReplica,
                                                Iterable<InstanceKey> exceptionIKs, PointsToGraph g,
                                                GraphDelta changed,
                                                HeapAbstractionFactory haf);

    @Override
    public abstract String toString();

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        if (useNumber == 0) {
            this.receiver = newVariable;
        }
        else {
            this.actuals.set(useNumber - 1, newVariable);
        }
    }

    @Override
    public List<ReferenceVariable> getUses() {
        List<ReferenceVariable> uses = new ArrayList<>(1 + actuals.size());
        uses.add(this.receiver);
        uses.addAll(this.actuals);
        return uses;
    }

    @Override
    public ReferenceVariable getDef() {
        return this.result;
    }

}
