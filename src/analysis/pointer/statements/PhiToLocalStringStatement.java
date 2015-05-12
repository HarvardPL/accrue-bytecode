package analysis.pointer.statements;

import java.util.List;

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

public class PhiToLocalStringStatement extends StringStatement {

    private final StringLikeVariable assignee;
    private final List<StringLikeVariable> uses;

    public PhiToLocalStringStatement(StringLikeVariable assignee, List<StringLikeVariable> uses, IMethod method) {
        super(method);
        this.assignee = assignee;
        // XXX: This should actually be a set
        this.uses = uses;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                       StmtAndContext originator, HeapAbstractionFactory haf,
                                       StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringLikeVariableReplica(context, this.assignee));
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        for (StringLikeVariable use : uses) {
            g.recordStringStatementUseDependency(new StringLikeVariableReplica(context, use), originator);
        }
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        StringLikeVariableReplica assigneeSVR = new StringLikeVariableReplica(context, this.assignee);

        g.recordStringStatementDefineDependency(assigneeSVR, originator);
    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        GraphDelta changes = new GraphDelta(g);

        for (StringLikeVariable use : uses) {
            changes.combine(g.activateStringSolutionVariable(new StringLikeVariableReplica(context, use)));
        }

        return changes;
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringLikeVariableReplica assigneeSVR = new StringLikeVariableReplica(context, this.assignee);

        GraphDelta newDelta = new GraphDelta(g);

        for(StringLikeVariable use : uses) {
            StringLikeVariableReplica usesvr = new StringLikeVariableReplica(context, use);
            newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(assigneeSVR, usesvr));
        }

        return newDelta;
    }

    @Override
    public String toString() {
        return this.assignee + " = phi(" + this.uses + ")";
    }

}
