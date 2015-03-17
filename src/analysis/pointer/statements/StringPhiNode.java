package analysis.pointer.statements;

import java.util.Set;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class StringPhiNode extends StringStatement {

    private final StringVariable v;
    private final Set<StringVariable> dependencies;

    public StringPhiNode(IMethod method, StringVariable v, Set<StringVariable> dependencies) {
        super(method);
        this.v = v;
        this.dependencies = dependencies;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica svr = new StringVariableReplica(context, this.v);
        GraphDelta newDelta = new GraphDelta(g);

        g.recordStringStatementDefineDependency(svr, originator);

        for (StringVariable dependency : dependencies) {
            StringVariableReplica dependentSVR = new StringVariableReplica(context, dependency);
            g.recordStringStatementDependency(dependentSVR, originator);
            g.recordStringVariableDependency(svr, dependentSVR);
            newDelta.combine(g.stringVariableReplicaUpperBounds(svr, dependentSVR));
        }

        return newDelta;
    }

    @Override
    public String toString() {
        return "StringPhi(" + this.v + ", " + this.dependencies + " )";
    }

}
