package android.statements;

import java.util.Iterator;

import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.LocalToFieldStatement;
import android.AndroidConstants;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.FieldReference;

public class AndroidLocalToFieldStatement extends LocalToFieldStatement {

    public AndroidLocalToFieldStatement(ReferenceVariable o, FieldReference f, ReferenceVariable v, IMethod m) {
        super(o, f, v, m);
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext sac) {
        ReferenceVariableReplica receiverRep = new ReferenceVariableReplica(context, getUses().get(0));
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();

        // Find if there are any targets that are interesting methods and handle those specially
        Iterator<InstanceKey> iter = delta == null ? g.pointsToIterator(receiverRep, sac)
                : delta.pointsToIterator(receiverRep);
        while (iter.hasNext()) {
            InstanceKey recHeapContext = iter.next();
            IClass klass = recHeapContext.getConcreteType();
            if (AndroidConstants.INTENT_CLASS.equals(recHeapContext.getConcreteType())) {
                IField f = cha.resolveField(klass, getField());
                // TODO keep track of locals that could be assigned here
            }
        }

        return super.process(context, haf, g, delta, registrar, sac);
    }
}
