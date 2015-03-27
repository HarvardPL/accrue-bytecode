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

public class EscapeViaReturnStringStatement extends StringStatement {

    private final ReferenceVariable rv;
    private final StringVariable sv;

    public EscapeViaReturnStringStatement(ReferenceVariable rv, StringVariable sv, IMethod method) {
        super(method);
        this.rv = rv;
        this.sv = sv;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica svr = new StringVariableReplica(context, this.sv);
        ReferenceVariableReplica rvr = new ReferenceVariableReplica(context, this.rv, haf);

        PointsToIterable pti = delta == null ? g : delta;

        GraphDelta newDelta = new GraphDelta(g);

        g.recordStringStatementUseDependency(svr, originator);

        AllocSiteNode allocationSite = AllocSiteNodeFactory.createGenerated("EscapeViaReturnStringStatement",
                                                                            AnalysisUtil.getClassHierarchy()
                                                                            .lookupClass(svr.getExpectedType()),
                                                                            this.getMethod(),
                                                                            null, // XXX : AllocSiteNodeFactory limits the each result to one alloc site
                                                                            false);

        newDelta.combine(g.addEdge(rvr, ((ReflectiveHAF) haf).recordStringlike(g.getAStringFor(svr),
                                                                               allocationSite,
                                                                               context)));

        return newDelta;
    }

    @Override
    public String toString() {
        return "EVRSS(" + this.rv + " = " + this.sv + ")";
    }
}
