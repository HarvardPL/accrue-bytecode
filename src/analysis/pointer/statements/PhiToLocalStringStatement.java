package analysis.pointer.statements;

import java.util.List;

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

public class PhiToLocalStringStatement extends StringStatement {

    private final StringVariable assignee;
    private final List<StringVariable> uses;

    public PhiToLocalStringStatement(StringVariable assignee, List<StringVariable> uses, IMethod method) {
        super(method);
        this.assignee = assignee;
        // XXX: This should actually be a set
        this.uses = uses;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                       StmtAndContext originator, HeapAbstractionFactory haf,
                                       StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringVariableReplica(context, this.assignee));
    }

    @Override
    protected void registerDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                        PointsToIterable pti, StmtAndContext originator, StatementRegistrar registrar) {
        StringVariableReplica assigneeSVR = new StringVariableReplica(context, this.assignee);

        g.recordStringStatementDefineDependency(assigneeSVR, originator);

        for (StringVariable use : uses) {
            g.recordStringStatementUseDependency(new StringVariableReplica(context, use), originator);
        }

    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica assigneeSVR = new StringVariableReplica(context, this.assignee);

        GraphDelta newDelta = new GraphDelta(g);

        for(StringVariable use : uses) {
            StringVariableReplica usesvr = new StringVariableReplica(context, use);
            newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(assigneeSVR, usesvr));
        }

        return newDelta;
    }

    @Override
    public String toString() {
        return this.assignee + " = phi(" + this.uses + ")";
    }

}
