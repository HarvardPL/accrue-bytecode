package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import analysis.pointer.analyses.AllocationName;
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
import analysis.pointer.statements.AllocSiteNodeFactory.ReflectiveAllocSiteNode;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class NewInstanceStatement<C extends Context> extends PointsToStatement<AllocationName<C>, C> {

    private final ReferenceVariable result;
    private ReferenceVariable receiver;
    private final CallSiteLabel callSite;
    private final MethodSummaryNodes calleeSummary;
    private final ReferenceVariable exception;

    // TODO: Is it bad that I don't have a one argument constructor?

    public NewInstanceStatement(IMethod m, ReferenceVariable result, ReferenceVariable receiver,
                                CallSiteLabel callSite, MethodSummaryNodes calleeSummary, ReferenceVariable exception) {
        super(m);
        this.result = result;
        this.receiver = receiver;
        this.callSite = callSite;
        this.calleeSummary = calleeSummary;
        this.exception = exception;
    }

    @Override
    public GraphDelta<AllocationName<C>, C> process(C context, HeapAbstractionFactory<AllocationName<C>, C> haf,
                                                    PointsToGraph<AllocationName<C>, C> g,
                                                    GraphDelta<AllocationName<C>, C> delta,
                                                    StatementRegistrar<AllocationName<C>, C> registrar,
                                                    StmtAndContext<AllocationName<C>, C> originator) {
        PointsToGraphNode receiverInContext = new ReferenceVariableReplica(context, this.receiver, haf);

        Iterator<AllocationName<C>> ans;
        if (delta == null) {
            ans = g.pointsToIterator(receiverInContext);
        }
        else {
            ans = delta.pointsToIterator(receiverInContext);
        }
        GraphDelta<AllocationName<C>, C> d = new GraphDelta<>(g);

        while (ans.hasNext()) {
            AllocationName<C> an = ans.next();
            AllocSiteNode allocsite = an.getAllocationSite();
            if (allocsite instanceof ReflectiveAllocSiteNode) {
                IClass newClass = ((ReflectiveAllocSiteNode) allocsite).getReflectedClass();
                AllocSiteNode asn = AllocSiteNodeFactory.createGenerated("NewInstance Reflective AllocSite",
                                                                         newClass,
                                                                         this.getMethod(),
                                                                         this.result,
                                                                         false);
                AllocationName<C> newHeapContext = haf.record(asn, context);
                assert newHeapContext != null;
                ReferenceVariableReplica resultInContext = new ReferenceVariableReplica(context, this.result, haf);
                // TODO: Does this do what I want?
                d = d.combine(g.addEdge(resultInContext, newHeapContext));
            }
            else {
                throw new RuntimeException("alocsite was the wrong type " + allocsite + " " + allocsite.getClass());
            }
        }
        return d;
    }

    @Override
    public String toString() {
        return result + " = " + receiver + ".newInstance()";
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
    public Collection<?> getReadDependencies(C ctxt, HeapAbstractionFactory<AllocationName<C>, C> haf) {
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
    public Collection<?> getWriteDependencies(C ctxt, HeapAbstractionFactory<AllocationName<C>, C> haf) {
        List<ReferenceVariableReplica> defs = new ArrayList<>(2);
        defs.add(new ReferenceVariableReplica(ctxt, result, haf));
        defs.add(new ReferenceVariableReplica(ctxt, exception, haf));
        return defs;
    }

}
