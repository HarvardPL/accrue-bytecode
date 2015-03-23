package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
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
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                  GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica assigneeRVR = new StringVariableReplica(context, this.assignee);

        GraphDelta newDelta = new GraphDelta(g);

        g.recordStringStatementDefineDependency(assigneeRVR, originator);

        List<StringVariableReplica> useRVRs = new ArrayList<>();
        for(StringVariable use : uses) {
            StringVariableReplica usesvr = new StringVariableReplica(context, use);
            g.recordStringStatementUseDependency(usesvr, originator);
            useRVRs.add(usesvr);
        }

        //        useRVRs.forEach(useRVR -> newDelta.combine(g.isSuperSetOf(assigneeRVR, useRVR)));
        for (StringVariableReplica useRVR : useRVRs) {
            newDelta.combine(g.recordStringVariableDependency(assigneeRVR, useRVR));
            newDelta.combine(g.stringVariableReplicaUpperBounds(assigneeRVR, useRVR));
        }

        return newDelta;
    }

    @Override
    public String toString() {
        return this.assignee + " = phi(" + this.uses + ")";
    }

}
