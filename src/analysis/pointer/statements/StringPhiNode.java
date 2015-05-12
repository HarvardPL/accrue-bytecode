package analysis.pointer.statements;

import java.util.Set;

import util.Logger;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.strings.StringLikeVariableReplica;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringLikeVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class StringPhiNode extends StringStatement {

    private final StringLikeVariable v;
    private final Set<StringLikeVariable> dependencies;

    public StringPhiNode(IMethod method, StringLikeVariable v, Set<StringLikeVariable> dependencies) {
        super(method);
        this.v = v;
        this.dependencies = dependencies;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                       StmtAndContext originator, HeapAbstractionFactory haf,
                                       StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringLikeVariableReplica(context, this.v));
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        for (StringLikeVariable dependency : dependencies) {
            StringLikeVariableReplica dependentSVR = new StringLikeVariableReplica(context, dependency);
            g.recordStringStatementUseDependency(dependentSVR, originator);
        }
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        StringLikeVariableReplica svr = new StringLikeVariableReplica(context, this.v);

        g.recordStringStatementDefineDependency(svr, originator);
    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        GraphDelta changes = new GraphDelta(g);

        for (StringLikeVariable dependency : dependencies) {
            StringLikeVariableReplica dependentSVR = new StringLikeVariableReplica(context, dependency);
            changes.combine(g.activateStringSolutionVariable(dependentSVR));
        }

        return changes;
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringLikeVariableReplica svr = new StringLikeVariableReplica(context, this.v);
        GraphDelta newDelta = new GraphDelta(g);

        Logger.println("[StringPhiNode] " + this);

        for (StringLikeVariable dependency : dependencies) {
            StringLikeVariableReplica dependentSVR = new StringLikeVariableReplica(context, dependency);
            newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(svr, dependentSVR));
        }

        return newDelta;
    }

    @Override
    public String toString() {
        return "StringPhi(" + this.v + ", " + this.dependencies + ")";
    }

}
