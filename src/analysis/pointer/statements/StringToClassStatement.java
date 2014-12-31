package analysis.pointer.statements;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.TypeReference;

public class StringToClassStatement<IK extends InstanceKey, C extends Context> extends PointsToStatement<IK, C> {

    ReferenceVariable result;
    ReferenceVariable string;

    // TODO: is it bad that I don't have a one argument constructor?

    public StringToClassStatement(IMethod m, ReferenceVariable result, ReferenceVariable string) {
        super(m);
        this.result = result;
        this.string = string;
    }

    @Override
    public GraphDelta<IK, C> process(C context, HeapAbstractionFactory<IK,C> haf, PointsToGraph<IK,C> g, GraphDelta<IK, C> delta,
                                     StatementRegistrar<IK, C> registrar, StmtAndContext<IK, C> originator) {
        PointsToGraphNode stringInContext = new ReferenceVariableReplica(context, this.string, haf);

        Iterator<IK> iks = delta.pointsToIterator(stringInContext);

        while (iks.hasNext()) {
            IK ik = iks.next();
            // XXX: This isn't actually correct, I just ignore instance keys that are not strings
            if (ik.getConcreteType().getReference().equals(TypeReference.JavaLangString)) {
                AllocSiteNode asn = AllocSiteNodeFactory.createReflective("Reflective Class Generation",
                                                                          stringToClass(ik),
                                                                          this.getMethod(),
                                                                          this.result);
                IK newHeapContext = haf.record(asn, context);
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
        // TODO Auto-generated method stub
        return null;
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
    public Collection<?> getReadDependencies(C ctxt, HeapAbstractionFactory<IK,C> haf) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Collection<?> getWriteDependencies(C ctxt, HeapAbstractionFactory<IK,C> haf) {
        // TODO Auto-generated method stub
        return null;
    }

}
