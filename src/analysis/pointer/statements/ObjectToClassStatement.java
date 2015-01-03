package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

public class ObjectToClassStatement<IK extends InstanceKey, C extends Context> extends PointsToStatement<IK, C> {

    private final ReferenceVariable result;
    private ReferenceVariable receiver;
    private final CallSiteLabel callSite;
    private final MethodSummaryNodes calleeSummary;
    private final ReferenceVariable exception;

    public ObjectToClassStatement(IMethod m, ReferenceVariable result, ReferenceVariable receiver,
                                  CallSiteLabel callSite, MethodSummaryNodes calleeSummary, ReferenceVariable exception) {
        super(m);
        this.result = result;
        this.receiver = receiver;
        this.callSite = callSite;
        this.calleeSummary = calleeSummary;
        this.exception = exception;
    }

    @Override
    public GraphDelta<IK, C> process(C context, HeapAbstractionFactory<IK, C> haf, PointsToGraph<IK, C> g,
                                     GraphDelta<IK, C> delta, StatementRegistrar<IK, C> registrar,
                                     StmtAndContext<IK, C> originator) {
        PointsToGraphNode receiverInContext = new ReferenceVariableReplica(context, this.receiver, haf);

        Iterator<IK> iks;
        if (delta == null) {
            iks = g.pointsToIterator(receiverInContext);
        }
        else {
            iks = delta.pointsToIterator(receiverInContext);
        }
        GraphDelta<IK, C> d = new GraphDelta<>(g);

        while (iks.hasNext()) {
            IK ik = iks.next();
            // XXX: This isn't actually correct, I just ignore instance keys that are not strings
            IClass klass = ik.getConcreteType();
            AllocSiteNode asn = AllocSiteNodeFactory.createReflective("Reflective Class Generation",
                                                                      klass,
                                                                      this.getMethod(),
                                                                      this.result);
            IK newHeapContext = haf.record(asn, context);
            assert newHeapContext != null;
            ReferenceVariableReplica resultInContext = new ReferenceVariableReplica(context, this.result, haf);
            // TODO: Does this do what I want?
            d = d.combine(g.addEdge(resultInContext, newHeapContext));
        }
        return d;
    }

    @Override
    public String toString() {
        return result + " = " + receiver + ".getClass()";
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0;
        receiver = newVariable;
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.singletonList(receiver);
    }

    @Override
    public ReferenceVariable getDef() {
        return result;
    }

    @Override
    public Collection<?> getReadDependencies(C ctxt, HeapAbstractionFactory<IK, C> haf) {
        List<ReferenceVariableReplica> uses = new ArrayList<>(3);

        ReferenceVariableReplica receiverRep = new ReferenceVariableReplica(ctxt, receiver, haf);
        uses.add(receiverRep);

        C calleeContext = haf.merge(this.callSite, null, ctxt);
        ReferenceVariableReplica ex = new ReferenceVariableReplica(calleeContext,
                                                                   this.calleeSummary.getException(),
                                                                   haf);
        uses.add(ex);

        // Say that we read the return of the callee.
        ReferenceVariableReplica n = new ReferenceVariableReplica(calleeContext, this.calleeSummary.getReturn(), haf);
        uses.add(n);
        return uses;
    }

    @Override
    public Collection<?> getWriteDependencies(C ctxt, HeapAbstractionFactory<IK, C> haf) {
        List<ReferenceVariableReplica> defs = new ArrayList<>(2);
        defs.add(new ReferenceVariableReplica(ctxt, result, haf));
        defs.add(new ReferenceVariableReplica(ctxt, exception, haf));
        return defs;
    }

}
