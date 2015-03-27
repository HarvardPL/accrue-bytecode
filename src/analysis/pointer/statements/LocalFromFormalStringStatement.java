package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class LocalFromFormalStringStatement extends StringStatement {

    private final StringVariable local;
    // this one cannot be final because of `replaceUse(..)`
    private ReferenceVariable formal;

    public LocalFromFormalStringStatement(StringVariable local, ReferenceVariable formal, IMethod method) {
        super(method);
        this.local = local;
        this.formal = formal;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                  GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica localRVR = new StringVariableReplica(context, this.local);
        PointsToGraphNode formalRVR = new ReferenceVariableReplica(context, this.formal, haf);
        PointsToIterable pti = delta == null ? g : delta;

        GraphDelta newDelta = new GraphDelta(g);

        g.recordStringStatementDefineDependency(localRVR, originator);

        newDelta.combine(g.stringVariableReplicaJoinAt(localRVR, pti.astringForPointsToGraphNode(formalRVR, originator)));

        return newDelta;
    }

    @Override
    public String toString() {
        return this.local + " = " + this.formal;
    }
}
