package analysis.pointer.statements;

import java.util.Set;

import util.Logger;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
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
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                       StmtAndContext originator, HeapAbstractionFactory haf,
                                       StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringVariableReplica(context, this.v));
    }

    @Override
    protected void registerDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                        PointsToIterable pti, StmtAndContext originator, StatementRegistrar registrar) {
        StringVariableReplica svr = new StringVariableReplica(context, this.v);

        g.recordStringStatementDefineDependency(svr, originator);

        for (StringVariable dependency : dependencies) {
            StringVariableReplica dependentSVR = new StringVariableReplica(context, dependency);
            g.recordStringStatementUseDependency(dependentSVR, originator);
        }
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica svr = new StringVariableReplica(context, this.v);
        GraphDelta newDelta = new GraphDelta(g);

        Logger.println("[StringPhiNode] " + this);

        for (StringVariable dependency : dependencies) {
            StringVariableReplica dependentSVR = new StringVariableReplica(context, dependency);
            newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(svr, dependentSVR));
        }

        return newDelta;
    }

    @Override
    public String toString() {
        return "StringPhi(" + this.v + ", " + this.dependencies + ")";
    }

}
