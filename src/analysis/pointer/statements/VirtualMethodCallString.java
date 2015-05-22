package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.List;

import util.OrderedPair;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.strings.StringLikeVariableReplica;
import analysis.pointer.registrar.MethodStringSummary;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringLikeVariable;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

public class VirtualMethodCallString extends MethodCallString {

    private final List<OrderedPair<StringLikeVariable, Integer>> stringArgumentAndParamNums;
    private final StringLikeVariable actualReturn;
    private final MethodReference declaredTarget;
    private final ReferenceVariable receiver;

    public VirtualMethodCallString(IMethod method,
                                   ArrayList<OrderedPair<StringLikeVariable, Integer>> stringArgumentAndParamNums,
                                   StringLikeVariable returnToVariable, MethodReference declaredTarget,
                                   ReferenceVariable receiver) {
        super(method);
        this.stringArgumentAndParamNums = stringArgumentAndParamNums;
        this.actualReturn = returnToVariable;
        this.declaredTarget = declaredTarget;
        this.receiver = receiver;
    }

    @Override
    protected boolean writersAreActive(Context context, PointsToGraph g, PointsToIterable pti,
                                       StmtAndContext originator, HeapAbstractionFactory haf,
                                       StatementRegistrar registrar) {
        boolean writersAreActive = false;
        if (this.actualReturn != null) {
            writersAreActive |= g.stringSolutionVariableReplicaIsActive(new StringLikeVariableReplica(context,
                                                                                                      this.actualReturn));
        }

        ReferenceVariableReplica receiverRVR = new ReferenceVariableReplica(context, this.receiver, haf);

        for (InstanceKey ik : pti.pointsToIterable(receiverRVR, originator)) {
            IMethod callee = this.resolveMethod(ik.getConcreteType(), receiverRVR.getExpectedType());
            if (!callee.isNative()) {
                MethodStringSummary summary = registrar.findOrCreateStringMethodSummary(callee);

                for (OrderedPair<StringLikeVariable, Integer> pair : this.stringArgumentAndParamNums) {
                    writersAreActive |= g.stringSolutionVariableReplicaIsActive(new StringLikeVariableReplica(context,
                                                                                                              summary.getFormals()
                                                                                                                     .get(pair.snd())));
                }
            }
        }

        return writersAreActive;
    }

    @Override
    protected void registerReadDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                            PointsToIterable pti, StmtAndContext originator,
                                            StatementRegistrar registrar) {
        ReferenceVariableReplica receiverRVR = new ReferenceVariableReplica(context, this.receiver, haf);

        for (InstanceKey ik : pti.pointsToIterable(receiverRVR, originator)) {
            IMethod callee = this.resolveMethod(ik.getConcreteType(), receiverRVR.getExpectedType());
            if (!callee.isNative()) {
                MethodStringSummary summary = registrar.findOrCreateStringMethodSummary(callee);

                for (OrderedPair<StringLikeVariable, Integer> pair : this.stringArgumentAndParamNums) {
                    StringLikeVariableReplica argument = new StringLikeVariableReplica(context, pair.fst());
                    g.recordStringStatementUseDependency(argument, originator);
                }

                assert (actualReturn == null) || (actualReturn != null && summary.getRet() != null) : "if actual return is non-null then summary better be non-null. summary is "
                        + summary + " actualReturn is " + actualReturn;
                if (actualReturn != null) {
                    StringLikeVariableReplica formalReturnSVR = new StringLikeVariableReplica(context, summary.getRet());
                    g.recordStringStatementUseDependency(formalReturnSVR, originator);
                }
            }
        }

    }

    @Override
    protected void registerWriteDependencies(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                             PointsToIterable pti, StmtAndContext originator,
                                             StatementRegistrar registrar) {
        ReferenceVariableReplica receiverRVR = new ReferenceVariableReplica(context, this.receiver, haf);

        for (InstanceKey ik : pti.pointsToIterable(receiverRVR, originator)) {
            IMethod callee = this.resolveMethod(ik.getConcreteType(), receiverRVR.getExpectedType());
            if (!callee.isNative()) {
                MethodStringSummary summary = registrar.findOrCreateStringMethodSummary(callee);

                for (OrderedPair<StringLikeVariable, Integer> pair : this.stringArgumentAndParamNums) {
                    StringLikeVariableReplica parameter = new StringLikeVariableReplica(context,
                                                                                        summary.getFormals()
                                                                                               .get(pair.snd()));
                    g.recordStringStatementDefineDependency(parameter, originator);
                }

                assert (actualReturn == null) || (actualReturn != null && summary.getRet() != null) : "if actual return is non-null then summary better be non-null. summary is "
                        + summary + " actualReturn is " + actualReturn;
                if (actualReturn != null) {
                    StringLikeVariableReplica actualReturnSVR = new StringLikeVariableReplica(context, actualReturn);

                    g.recordStringStatementDefineDependency(actualReturnSVR, originator);
                }
            }
        }

    }

    @Override
    protected GraphDelta activateReads(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                       PointsToIterable pti, StmtAndContext originator, StatementRegistrar registrar) {
        ReferenceVariableReplica receiverRVR = new ReferenceVariableReplica(context, this.receiver, haf);

        GraphDelta changes = new GraphDelta(g);

        for (InstanceKey ik : pti.pointsToIterable(receiverRVR, originator)) {
            IMethod callee = this.resolveMethod(ik.getConcreteType(), receiverRVR.getExpectedType());
            if (!callee.isNative()) {
                MethodStringSummary summary = registrar.findOrCreateStringMethodSummary(callee);

                for (OrderedPair<StringLikeVariable, Integer> pair : this.stringArgumentAndParamNums) {
                    StringLikeVariableReplica argument = new StringLikeVariableReplica(context, pair.fst());
                    changes.combine(g.activateStringSolutionVariable(argument));
                }

                assert (actualReturn == null) || (actualReturn != null && summary.getRet() != null) : "if actual return is non-null then summary better be non-null. sumamry is "
                        + summary + " actualReturn is " + actualReturn;
                if (actualReturn != null) {
                    StringLikeVariableReplica formalReturnSVR = new StringLikeVariableReplica(context, summary.getRet());

                    changes.combine(g.activateStringSolutionVariable(formalReturnSVR));
                }
            }
        }

        return changes;
    }

    @Override
    public GraphDelta updateSolution(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                     PointsToIterable pti, StatementRegistrar registrar, StmtAndContext originator) {
        ReferenceVariableReplica receiverRVR = new ReferenceVariableReplica(context, this.receiver, haf);

        GraphDelta newDelta = new GraphDelta(g);

        for (InstanceKey ik : pti.pointsToIterable(receiverRVR, originator)) {
            IMethod callee = this.resolveMethod(ik.getConcreteType(), receiverRVR.getExpectedType());
            if (!callee.isNative()) {
                MethodStringSummary summary = registrar.findOrCreateStringMethodSummary(callee);

                ArrayList<OrderedPair<StringLikeVariable, StringLikeVariable>> stringArgumentAndParameters = new ArrayList<>();
                for (OrderedPair<StringLikeVariable, Integer> pair : this.stringArgumentAndParamNums) {
                    OrderedPair<StringLikeVariable, StringLikeVariable> newpair = new OrderedPair<>(pair.fst(),
                                                                                                    summary.getFormals()
                                                                                                           .get(pair.snd()));
                    stringArgumentAndParameters.add(newpair);
                }

                newDelta.combine(MethodCallString.processCall(this.actualReturn,
                                                              summary.getRet(),
                                                              stringArgumentAndParameters,
                                                              context,
                                                              g));
            }
        }
        return newDelta;
    }

    /* copy-pasted from VirtualCallStatement */
    private IMethod resolveMethod(IClass receiverConcreteType, TypeReference receiverExpectedType) {
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
        // XXXX possible point of contention...!@!
        synchronized (cha) {
            IMethod resolvedCallee = cha.resolveMethod(receiverConcreteType, this.declaredTarget.getSelector());
            if (resolvedCallee == null) {
                // XXX Try the type of the reference variable instead
                // This is probably a variable created for the return of a native method, then cast down
                if (PointsToAnalysis.outputLevel >= 1) {
                    System.err.println("Could not resolve " + receiverConcreteType + " "
                            + this.declaredTarget.getSelector());
                    System.err.println("\ttrying reference variable type " + cha.lookupClass(receiverExpectedType));
                }
                resolvedCallee = cha.resolveMethod(cha.lookupClass(receiverExpectedType),
                                                   this.declaredTarget.getSelector());
            }
            return resolvedCallee;
        }
    }

    @Override
    public String toString() {
        return "VirtualMethodCallString [stringArgumentAndParamNums=" + stringArgumentAndParamNums + ", actualReturn="
                + actualReturn + ", declaredTarget=" + declaredTarget + ", receiver=" + receiver + ", inside method "
                + this.getMethod() + "]";
    }

}
