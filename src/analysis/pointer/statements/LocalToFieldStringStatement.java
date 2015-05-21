package analysis.pointer.statements;

import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.strings.StringLikeVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringLikeVariable;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;

public class LocalToFieldStringStatement extends StringStatement {

    private final StringLikeVariable vUse;
    private final StringLikeVariable vDef;
    private final ReferenceVariable o;
    private final IField f;

    public LocalToFieldStringStatement(StringLikeVariable vDef, StringLikeVariable vUse, ReferenceVariable o, FieldReference f,
                                       IMethod method) {
        super(method);
        this.vUse = vUse;
        this.vDef = vDef;
        this.o = o;
        this.f = AnalysisUtil.getClassHierarchy().resolveField(f);
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti, StmtAndContext originator, HeapAbstractionFactory haf, StatementRegistrar registrar) {
        ReferenceVariableReplica oRVR = new ReferenceVariableReplica(context, this.o, haf);

        boolean writersAreActive = false;

        for (InstanceKey oIK : g.pointsToIterable(oRVR, originator)) {
            ObjectField of = new ObjectField(oIK, this.f);
            writersAreActive |= g.stringSolutionVariableReplicaIsActive(of);
        }
        return writersAreActive;
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        StringLikeVariableReplica vUseSVR = new StringLikeVariableReplica(context, this.vUse);

        g.recordStringStatementUseDependency(vUseSVR, originator);
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        ReferenceVariableReplica oRVR = new ReferenceVariableReplica(context, this.o, haf);

        for (InstanceKey oIK : g.pointsToIterable(oRVR, originator)) {
            ObjectField of = new ObjectField(oIK, this.f);
            g.recordStringStatementDefineDependency(of, originator);
        }

    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        StringLikeVariableReplica vUseSVR = new StringLikeVariableReplica(context, this.vUse);

        return g.activateStringSolutionVariable(vUseSVR);
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringLikeVariableReplica vUseSVR = new StringLikeVariableReplica(context, this.vUse);
        ReferenceVariableReplica oRVR = new ReferenceVariableReplica(context, this.o, haf);

        GraphDelta newDelta = new GraphDelta(g);

        for (InstanceKey oIK : g.pointsToIterable(oRVR, originator)) {
            ObjectField of = new ObjectField(oIK, this.f);

            if (g.stringSolutionVariableReplicaIsActive(of)) {
                newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(of, vUseSVR));
            }
        }

        return newDelta;
    }

    @Override
    public String toString() {
        return "LTFSS(" + this.o + "." + this.f + " = " + this.vUse + ")";
    }

}
