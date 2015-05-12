package analysis.pointer.statements;

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

public class LocalToLocalString extends StringStatement {

    private final StringLikeVariable left;
    private final StringLikeVariable right;

    public LocalToLocalString(StringLikeVariable left, StringLikeVariable right, IMethod method) {
        super(method);
        this.left = left;
        this.right = right;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti, StmtAndContext originator, HeapAbstractionFactory haf, StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringLikeVariableReplica(context, this.left));
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        StringLikeVariableReplica rightsvr = new StringLikeVariableReplica(context, this.right);

        g.recordStringStatementUseDependency(rightsvr, originator);
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        StringLikeVariableReplica leftsvr = new StringLikeVariableReplica(context, this.left);

        g.recordStringStatementDefineDependency(leftsvr, originator);
    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        StringLikeVariableReplica rightsvr = new StringLikeVariableReplica(context, this.right);

        return g.activateStringSolutionVariable(rightsvr);
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringLikeVariableReplica leftR = new StringLikeVariableReplica(context, this.left);
        StringLikeVariableReplica rightR = new StringLikeVariableReplica(context, this.right);

        GraphDelta newDelta = new GraphDelta(g);

        newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(leftR, rightR));

        return newDelta;
    }

    @Override
    public String toString() {
        return "LocalToLocalString [left=" + left + ", right=" + right + "]";
    }


}
