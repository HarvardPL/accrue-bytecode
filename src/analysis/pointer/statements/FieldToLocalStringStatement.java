package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.StringInstanceKey;
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
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                              GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica vSVR = new StringVariableReplica(context, this.v);
        PointsToGraphNode oRVR = new ReferenceVariableReplica(context, this.o, haf);

        PointsToIterable pti = (PointsToIterable) (delta == null ? g : delta);

        GraphDelta newDelta = new GraphDelta(g);

        for (InstanceKey oIK : pti.pointsToIterable(oRVR, originator)) {
            ObjectField f = new ObjectField(oIK, this.field);

            for (InstanceKey fIK : pti.pointsToIterable(f, originator)) {
                for (StringInstanceKey sik : g.stringsForInstanceKey(fIK)) {
                    newDelta.combine(g.stringVariableReplicaJoinAr(vSVR, sik));
                }
            }
        }
        return newDelta;
    }

    @Override
    public String toString() {
        return v + " = " + o + "." + field;
    }

}
