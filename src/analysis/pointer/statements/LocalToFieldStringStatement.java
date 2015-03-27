package analysis.pointer.statements;

import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;

public class LocalToFieldStringStatement extends StringStatement {

    private final StringVariable vUse;
    private final StringVariable vDef;
    private final ReferenceVariable o;
    private final FieldReference f;

    public LocalToFieldStringStatement(StringVariable vDef, StringVariable vUse, ReferenceVariable o, FieldReference f,
                                       IMethod method) {
        super(method);
        this.vUse = vUse;
        this.vDef = vDef;
        this.o = o;
        this.f = f;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica vUseSVR = new StringVariableReplica(context, this.vUse);
        StringVariableReplica vDefSVR = new StringVariableReplica(context, this.vDef);
        ReferenceVariableReplica oRVR = new ReferenceVariableReplica(context, this.o, haf);

        PointsToIterable pti = delta == null ? g : delta;

        GraphDelta newDelta = new GraphDelta(g);

        g.recordStringStatementDefineDependency(vDefSVR, originator);
        g.recordStringStatementUseDependency(vUseSVR, originator);
        newDelta.combine(g.recordStringVariableIndirectDependency(vDefSVR, vUseSVR));

        for (InstanceKey oIK : pti.pointsToIterable(oRVR, originator)) {
            ObjectField of = new ObjectField(oIK, this.f);

            AllocSiteNode allocationSite = AllocSiteNodeFactory.createGenerated("LocalToFieldStringStatement",
                                                                                AnalysisUtil.getClassHierarchy()
                                                                                            .lookupClass(vUseSVR.getExpectedType()),
                                                                                this.getMethod(),
                                                                                null, // XXX : AllocSiteNodeFactory limits the each result to one alloc site
                                                                                false);

            newDelta.combine(g.addEdgeToAString(of, vUseSVR, allocationSite, context, originator));

            newDelta.combine(g.stringVariableReplicaJoinAt(vDefSVR, pti.astringForPointsToGraphNode(of, originator)));
        }

        return newDelta;
    }

    @Override
    public String toString() {
        return "LTFSS(" + this.o + "." + this.f + " = " + this.vUse + ")";
    }

}
