package analysis.pointer.statements;

import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

public class ReturnStringStatement extends StringStatement {

    static final IClass JavaLangStringIClass = AnalysisUtil.getClassHierarchy()
                                                           .lookupClass(TypeReference.JavaLangString);

    private StringVariable v;
    private final ReferenceVariable summary;

    public ReturnStringStatement(StringVariable v, ReferenceVariable summary, IMethod method) {
        super(method);
        this.v = v;
        this.summary = summary;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica vsvr = new StringVariableReplica(context, this.v);
        ReferenceVariableReplica summaryRVR = new ReferenceVariableReplica(context, summary, haf);
        AllocSiteNode allocationSite = AllocSiteNodeFactory.createGenerated("stringAnalysisReturn",
                                                                            AnalysisUtil.getClassHierarchy()
                                                                                        .lookupClass(vsvr.getExpectedType()),
                                                                            this.method,
                                                                            null, /* XXX: this is probably a bug, not sure why cannot generate more than one IK for a given result */
                                                                            false);

        GraphDelta newDelta = new GraphDelta(g);

        g.recordStringStatementUseDependency(vsvr, originator);

        newDelta.combine(g.addEdgeToAString(summaryRVR, vsvr, allocationSite, context, originator));

        return newDelta;
    }

    @Override
    public String toString() {
        return "return " + this.v;
    }

}
