package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.List;

import util.Logger;
import util.OrderedPair;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.MethodStringSummary;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

public class VirtualMethodCallString extends MethodCallString {

    private final List<OrderedPair<StringVariable, Integer>> stringArgumentAndParamNums;
    private final StringVariable actualReturn;
    private final MethodReference declaredTarget;
    private final ReferenceVariable receiver;

    public VirtualMethodCallString(IMethod method,
                                         ArrayList<OrderedPair<StringVariable, Integer>> stringArgumentAndParamNums,
                                         StringVariable returnToVariable, MethodReference declaredTarget,
                                         ReferenceVariable receiver) {
        super(method);
        this.stringArgumentAndParamNums = stringArgumentAndParamNums;
        this.actualReturn = returnToVariable;
        this.declaredTarget = declaredTarget;
        this.receiver = receiver;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        ReferenceVariableReplica receiverRVR = new ReferenceVariableReplica(context, this.receiver, haf);

        PointsToIterable pti = delta == null ? g : delta;

        Iterable<InstanceKey> iks = pti.pointsToIterable(receiverRVR, originator);

        GraphDelta newDelta = new GraphDelta(g);

        for (InstanceKey ik : iks) {
            IMethod callee = this.resolveMethod(ik.getConcreteType(), receiverRVR.getExpectedType());
            MethodStringSummary summary = registrar.findOrCreateStringMethodSummary(callee);

            ArrayList<OrderedPair<StringVariable, StringVariable>> stringArgumentAndParameters = new ArrayList<>();
            for (OrderedPair<StringVariable, Integer> pair : this.stringArgumentAndParamNums) {
                OrderedPair<StringVariable, StringVariable> newpair = new OrderedPair<>(pair.fst(),
                                                                                        summary.getFormals()
                                                                                               .get(pair.snd()));
                stringArgumentAndParameters.add(newpair);
            }

            newDelta.combine(this.processCall(this.actualReturn,
                                              summary.getRet(),
                                              stringArgumentAndParameters,
                                              context,
                                              haf,
                                              g,
                                              delta,
                                              registrar,
                                              originator));
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
                    Logger.println("Could not resolve " + receiverConcreteType + " "
                            + this.declaredTarget.getSelector());
                    Logger.println("\ttrying reference variable type " + cha.lookupClass(receiverExpectedType));
                }
                resolvedCallee = cha.resolveMethod(cha.lookupClass(receiverExpectedType),
                                                   this.declaredTarget.getSelector());
            }
            return resolvedCallee;
        }
    }

    @Override
    public String toString() {
        return "VirtualMethodCallStringEscape [stringArgumentAndParamNums=" + stringArgumentAndParamNums
                + ", returnToVariable=" + actualReturn + ", declaredTarget=" + declaredTarget + ", receiver="
                + receiver + "]";
    }

}
