package analysis.pointer.statements;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import analysis.pointer.analyses.AllocationName;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.AllocSiteNodeFactory.ReflectiveAllocSiteNode;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class NewInstanceStatement<C extends Context> extends PointsToStatement<AllocationName<C>, C> {

    ReferenceVariable result;
    ReferenceVariable receiver;

    // TODO: Is it bad that I don't have a one argument constructor?

    public NewInstanceStatement(IMethod m, ReferenceVariable result, ReferenceVariable receiver) {
        super(m);
        this.result = result;
        this.receiver = receiver;
    }

    @Override
    public GraphDelta<AllocationName<C>, C> process(C context, HeapAbstractionFactory<AllocationName<C>, C> haf,
                                                    PointsToGraph<AllocationName<C>, C> g,
                                                    GraphDelta<AllocationName<C>, C> delta,
                                                    StatementRegistrar<AllocationName<C>, C> registrar,
                                                    StmtAndContext<AllocationName<C>, C> originator) {
        PointsToGraphNode receiverInContext = new ReferenceVariableReplica(context, this.receiver, haf);

        Iterator<AllocationName<C>> ans = delta.pointsToIterator(receiverInContext);

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
                delta = delta.combine(g.addEdge(resultInContext, newHeapContext));
            }
        }
        return delta;
    }

    @Override
    public String toString() {
        return "NewInstanceStatement";
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        // TODO Auto-generated method stub

    }

    @Override
    public List<ReferenceVariable> getUses() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ReferenceVariable getDef() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Collection<?> getReadDependencies(C ctxt, HeapAbstractionFactory<AllocationName<C>, C> haf) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Collection<?> getWriteDependencies(C ctxt, HeapAbstractionFactory<AllocationName<C>, C> haf) {
        // TODO Auto-generated method stub
        return null;
    }

}
