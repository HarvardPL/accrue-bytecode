package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.ObjectField;
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
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;

public class FieldToLocalStringStatement extends StringStatement {

    private final StringVariable v;
    // this cannot be final because of `replaceUse(..)`
    private ReferenceVariable o;
    private final FieldReference field;

    public FieldToLocalStringStatement(StringVariable svv, ReferenceVariable o, FieldReference field, IMethod method) {
        super(method);
        this.v = svv;
        this.o = o;
        this.field = field;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                       StmtAndContext originator, HeapAbstractionFactory haf,
                                       StatementRegistrar registrar) {
        return g.stringSolutionVariableReplicaIsActive(new StringVariableReplica(context, this.v));
    }

    @Override
    protected void registerDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                        PointsToIterable pti, StmtAndContext originator, StatementRegistrar registrar) {
        StringVariableReplica vSVR = new StringVariableReplica(context, this.v);
        PointsToGraphNode oRVR = new ReferenceVariableReplica(context, this.o, haf);

        g.recordStringStatementDefineDependency(vSVR, originator);

        for (InstanceKey oIK : pti.pointsToIterable(oRVR, originator)) {
            ObjectField f = new ObjectField(oIK, this.field);

            g.recordStringStatementUseDependency(f, originator);
        }
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica vSVR = new StringVariableReplica(context, this.v);
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
