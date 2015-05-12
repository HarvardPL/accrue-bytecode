package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.ObjectField;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.strings.StringLikeVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringLikeVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;

public class FieldToLocalStringStatement extends StringStatement {

    private final StringLikeVariable v;
    // this cannot be final because of `replaceUse(..)`
    private ReferenceVariable o;
    private final FieldReference field;

    public FieldToLocalStringStatement(StringLikeVariable svv, ReferenceVariable o, FieldReference field, IMethod method) {
        super(method);
        this.v = svv;
        this.o = o;
        this.field = field;
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
        PointsToGraphNode oRVR = new ReferenceVariableReplica(context, this.o, haf);

        for (InstanceKey oIK : pti.pointsToIterable(oRVR, originator)) {
            ObjectField f = new ObjectField(oIK, this.field);

            g.recordStringStatementUseDependency(f, originator);
        }
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        StringLikeVariableReplica vSVR = new StringLikeVariableReplica(context, this.v);

        g.recordStringStatementDefineDependency(vSVR, originator);
    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g, PointsToIterable pti,
                                   StmtAndContext originator, StatementRegistrar registrar) {
        PointsToGraphNode oRVR = new ReferenceVariableReplica(context, this.o, haf);

        GraphDelta changes = new GraphDelta(g);

        for (InstanceKey oIK : pti.pointsToIterable(oRVR, originator)) {
            ObjectField f = new ObjectField(oIK, this.field);

            changes.combine(g.activateStringSolutionVariable(f));
        }

        return changes;
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringLikeVariableReplica vSVR = new StringLikeVariableReplica(context, this.v);
        PointsToGraphNode oRVR = new ReferenceVariableReplica(context, this.o, haf);

        GraphDelta newDelta = new GraphDelta(g);

        for (InstanceKey oIK : pti.pointsToIterable(oRVR, originator)) {
            ObjectField f = new ObjectField(oIK, this.field);

            newDelta.combine(g.stringSolutionVariableReplicaUpperBounds(vSVR, f));
        }
        return newDelta;
    }

    @Override
    public String toString() {
        return this.v + " = " + o + "." + field;
    }

}
