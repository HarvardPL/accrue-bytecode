package analysis.pointer.statements;

import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
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

public class EscapeViaMethodStringStatement extends StringStatement {

    private final ReferenceVariable rv;
    private final StringVariable svuse;
    private final StringVariable svdef;

    public EscapeViaMethodStringStatement(ReferenceVariable rv, StringVariable svuse, StringVariable svdef,
                                          IMethod method) {
        super(method);
        this.rv = rv;
        this.svuse = svuse;
        this.svdef = svdef;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica svruse = new StringVariableReplica(context, this.svuse);
        StringVariableReplica svrdef = new StringVariableReplica(context, this.svdef);
        ReferenceVariableReplica rvr = new ReferenceVariableReplica(context, this.rv, haf);

        PointsToIterable pti = delta == null ? g : delta;

        GraphDelta newDelta = new GraphDelta(g);

        g.recordStringStatementUseDependency(svruse, originator);
        g.recordStringStatementDefineDependency(svrdef, originator);

        AllocSiteNode allocationSite = AllocSiteNodeFactory.createGenerated("EscapeViaMethodStringStatement",
                                                                            AnalysisUtil.getClassHierarchy()
                                                                                        .lookupClass(svruse.getExpectedType()),
                                                                            this.getMethod(),
                                                                            null, // XXX : AllocSiteNodeFactory limits the each result to one alloc site
                                                                            false);

        newDelta.combine(g.addEdge(rvr, ((ReflectiveHAF) haf).recordStringlike(g.getAStringFor(svruse),
                                                                               allocationSite,
                                                                               context)));

        newDelta.combine(g.stringVariableReplicaJoinAt(svrdef, pti.astringForPointsToGraphNode(rvr, originator)));

        return newDelta;
    }

    @Override
    public String toString() {
        return "EVMSS(" + this.rv + " = " + this.svuse + " and " + this.svdef + " = " + this.rv + ")";
    }
}
