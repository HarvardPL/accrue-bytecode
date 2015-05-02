package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;

public class LocalToFieldStringStatement extends StringStatement {

    private final StringVariable vUse;
    private final StringVariable vDef;
    private final ReferenceVariable o;
    private final FieldReference f;

    public LocalToFieldStringStatement(StringVariable vDef, StringVariable vUse, ReferenceVariable o, FieldReference f,
                                       IMethod method) {
        super(method);
        this.vUse = vUse;
        this.vDef = vDef;
        this.o = o;
        this.f = f;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti, StmtAndContext originator, HeapAbstractionFactory haf, StatementRegistrar registrar) {
        ReferenceVariableReplica oRVR = new ReferenceVariableReplica(context, this.o, haf);
        StringVariableReplica vDefSVR = new StringVariableReplica(context, this.vDef);

        boolean writersAreActive = false;

        writersAreActive |= g.stringSolutionVariableReplicaIsActive(vDefSVR);

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
        StringVariableReplica vUseSVR = new StringVariableReplica(context, this.vUse);

        g.recordStringStatementUseDependency(vUseSVR, originator);
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        StringVariableReplica vDefSVR = new StringVariableReplica(context, this.vDef);
        ReferenceVariableReplica oRVR = new ReferenceVariableReplica(context, this.o, haf);

        // XXX: Hack, we set the def to top to deal with string escape
        g.recordStringStatementDefineDependency(vDefSVR, originator);

        for (InstanceKey oIK : g.pointsToIterable(oRVR, originator)) {
            ObjectField of = new ObjectField(oIK, this.f);
            g.recordStringStatementDefineDependency(of, originator);
        }

    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                 StmtAndContext originator, StatementRegistrar registrar) {
        StringVariableReplica vUseSVR = new StringVariableReplica(context, this.vUse);

        return g.activateStringSolutionVariable(vUseSVR);
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica vUseSVR = new StringVariableReplica(context, this.vUse);
        StringVariableReplica vDefSVR = new StringVariableReplica(context, this.vDef);
        ReferenceVariableReplica oRVR = new ReferenceVariableReplica(context, this.o, haf);

        GraphDelta newDelta = new GraphDelta(g);

        g.recordStringStatementDefineDependency(vDefSVR, originator);
        g.recordStringStatementUseDependency(vUseSVR, originator);

        for (InstanceKey oIK : g.pointsToIterable(oRVR, originator)) {
            ObjectField of = new ObjectField(oIK, this.f);

            if (g.stringSolutionVariableReplicaIsActive(of)) {
                newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(of, vUseSVR));
            }
        }
        // XXX: Hack to deal with escape
        newDelta.combine(g.stringSolutionVariableReplicaJoinAt(vDefSVR, ((ReflectiveHAF) haf).getAStringTop()));

        return newDelta;
    }

    @Override
    public String toString() {
        return "LTFSS(" + this.o + "." + this.f + " = " + this.vUse + ")";
    }

}
