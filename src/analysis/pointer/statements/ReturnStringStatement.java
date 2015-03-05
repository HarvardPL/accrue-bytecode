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
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
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
        StringVariableReplica vRVR = new StringVariableReplica(context, this.v);
        AllocSiteNode asn = AllocSiteNodeFactory.createGenerated("stringAnalysisReturn",
                                                                 JavaLangStringIClass,
                                                                 this.method,
                                                                 summary,
                                                                 false);
        InstanceKey newIK = haf.record(asn, context);
        assert newIK != null;

        g.ikDependsOnStringVariable(newIK, vRVR);

        ReferenceVariableReplica summaryRVR = new ReferenceVariableReplica(context, summary, haf);
        return g.addEdge(summaryRVR, newIK);
    }

    @Override
    public String toString() {
        return "return " + this.v;
    }

}
