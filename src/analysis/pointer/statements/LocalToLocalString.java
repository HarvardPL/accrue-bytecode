package analysis.pointer.statements;

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

public class LocalToLocalString extends StringStatement {

    private final StringVariable left;
    private final StringVariable right;

    public LocalToLocalString(StringVariable left, StringVariable right, IMethod method) {
        super(method);
        this.left = left;
        this.right = right;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti, StmtAndContext originator, HeapAbstractionFactory haf, StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringVariableReplica(context, this.left));
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        StringVariableReplica rightsvr = new StringVariableReplica(context, this.right);

        g.recordStringStatementUseDependency(rightsvr, originator);
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        StringVariableReplica leftsvr = new StringVariableReplica(context, this.left);

        g.recordStringStatementDefineDependency(leftsvr, originator);
    }

    @Override
    protected void activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        StringVariableReplica rightsvr = new StringVariableReplica(context, this.right);

        g.activateStringSolutionVariable(rightsvr);
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica formalReturnSVR = new StringVariableReplica(context, this.left);
        StringVariableReplica svr = new StringVariableReplica(context, this.right);

        GraphDelta newDelta = new GraphDelta(g);

        newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(formalReturnSVR, svr));

        return newDelta;
    }

    @Override
    public String toString() {
        return "LocalToLocalString [formalReturn=" + left + ", sv=" + right + "]";
    }


}
