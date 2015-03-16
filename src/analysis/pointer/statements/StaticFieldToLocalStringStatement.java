package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class StaticFieldToLocalStringStatement extends StringStatement {

    private final StringVariable v;
    // this cannot be final because of `replaceUses(..)`
    private StringVariable f;

    public StaticFieldToLocalStringStatement(StringVariable v, StringVariable f, IMethod method) {
        super(method);
        this.v = v;
        this.f = f;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica vRVR = new StringVariableReplica(context, this.v);
        StringVariableReplica fRVR = new StringVariableReplica(context, this.f);

        g.recordStringStatementDependency(fRVR, originator);

        g.recordStringVariableDependency(vRVR, fRVR);

        return g.stringVariableReplicaUpperBounds(vRVR, fRVR);
    }

    @Override
    public String toString() {
        return v + " = " + "CLASSNAME." + f;
    }

}
