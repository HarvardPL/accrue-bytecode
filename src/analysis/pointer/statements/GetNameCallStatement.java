package analysis.pointer.statements;

import util.FiniteSet;
import analysis.pointer.analyses.ClassInstanceKey;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.strings.StringLikeVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringLikeVariable;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.functions.Function;

public class GetNameCallStatement extends StringStatement {
    private final ReferenceVariable o;
    private final StringLikeVariable v;

    protected GetNameCallStatement(IMethod method, ReferenceVariable o, StringLikeVariable v) {
        super(method);
        this.o = o;
        this.v = v;
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
        // no string dependencies
    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        g.recordStringStatementDefineDependency(new StringLikeVariableReplica(context, this.v), originator);
    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                       PointsToIterable pti, StmtAndContext originator, StatementRegistrar registrar) {
        return new GraphDelta(g);
    }

    @Override
    protected GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                        PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        ReferenceVariableReplica rvr = new ReferenceVariableReplica(context, this.o, haf);
        StringLikeVariableReplica svr = new StringLikeVariableReplica(context, this.v);

        GraphDelta changed = new GraphDelta(g);

        for (InstanceKey ik : pti.pointsToIterable(rvr, originator)) {
            if (ik instanceof ClassInstanceKey) {
                ClassInstanceKey cik = (ClassInstanceKey) ik;
                FiniteSet<String> strings = cik.getReflectedType().map(new Function<IClass, String>() {
                    @Override
                    public String apply(IClass object) {
                        assert object.getName().toString().charAt(0) == 'L';
                        return object.getName().toString().replace('/', '.').substring(1);
                    }
                });

                System.err.println("[GetNameCallStatement] ___________________________");
                System.err.println("[GetNameCallStatement] in method: " + this.getMethod());
                System.err.println("[GetNameCallStatement] svr is: " + svr);
                System.err.println("[GetNameCallStatement] getting names: " + strings);

                changed.combine(g.stringSolutionVariableReplicaJoinAt(svr,
                                                                      ((ReflectiveHAF) haf).getAStringFromFiniteStringSet(strings)));
            }
        }

        return changed;
    }

    @Override
    public String toString() {
        return "GetNameCallStatement [o=" + o + ", v=" + v + "]";
    }

}
