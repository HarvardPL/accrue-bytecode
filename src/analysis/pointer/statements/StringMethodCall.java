package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.List;

import analysis.AnalysisUtil;
import analysis.StringAndReflectiveUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.FlowSensitiveStringVariableFactory;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.MethodReference;

public class StringMethodCall extends StringStatement {
    private static final int MAX_STRING_SET_SIZE = 5;

    private final CallSiteReference callSite;
    private final MethodEnum invokedMethod;
    private final IMethod invokingMethod;
    private final StringVariable result;
    private final StringVariable receiverUse;
    private final StringVariable receiverDef;
    private final List<StringVariable> arguments;
    private final FlowSensitiveStringVariableFactory stringVariableFactory;

    private enum MethodEnum {
        concatM, somethingElseM
    }

    private static MethodEnum imethodToMethodEnum(IMethod m) {
        if (m.equals(StringAndReflectiveUtil.stringBuilderAppendStringBuilderIMethod)
                || m.equals(StringAndReflectiveUtil.stringBuilderAppendStringIMethod)) {
            return MethodEnum.concatM;
        }
        else {
            return MethodEnum.somethingElseM;
        }
    }

    public StringMethodCall(CallSiteReference callSite, IMethod method, MethodReference declaredTarget,
                            StringVariable svresult, StringVariable svreceiverUse, StringVariable svreceiverDef,
                            List<StringVariable> svarguments, FlowSensitiveStringVariableFactory stringVariableFactory) {
        super(method);
        this.callSite = callSite;
        this.invokedMethod = imethodToMethodEnum(AnalysisUtil.getClassHierarchy().resolveMethod(declaredTarget));
        this.invokingMethod = method;
        this.result = svresult;
        this.receiverUse = svreceiverUse;
        this.receiverDef = svreceiverDef;
        this.arguments = svarguments;
        this.stringVariableFactory = stringVariableFactory;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica resultSVR = new StringVariableReplica(context, this.result);
        StringVariableReplica receiverUseSVR = new StringVariableReplica(context, this.receiverUse);
        StringVariableReplica receiverDefSVR = new StringVariableReplica(context, this.receiverDef);
        //        List<StringVariableReplica> argumentRVRs = this.arguments.stream()
        //                                                                 .map(a -> new StringVariableReplica(context, a, haf))
        //                                                                 .collect(Collectors.toList());
        List<StringVariableReplica> argumentSVRs = new ArrayList<>();
        for (StringVariable argument : this.arguments) {
            argumentSVRs.add(new StringVariableReplica(context, argument));
        }

        PointsToIterable pti = delta == null ? g : delta;

        switch (this.invokedMethod) {
        case concatM: {
            GraphDelta newDelta = new GraphDelta(g);

            // the first argument is a copy of the "this" argument
            assert argumentSVRs.size() == 2 : argumentSVRs.size();

            g.recordStringDependency(receiverUseSVR, originator);
            System.err.println("[concatM] Using " + receiverUseSVR + " = " + g.getAStringFor(receiverUseSVR));
            g.recordStringDependency(argumentSVRs.get(1), originator);
            System.err.println("[concatM] Using " + argumentSVRs.get(1) + " = " + g.getAStringFor(argumentSVRs.get(1)));

            AString maybeReceiverAString = g.getAStringFor(receiverUseSVR);
            AString maybeArgumentAString = g.getAStringFor(argumentSVRs.get(1));

            System.err.println("[concatM] I am " + this);

            AString newSIK = maybeReceiverAString.concat(maybeArgumentAString);
            System.err.println("[concatM] g.stringVariableReplicaJoinAt(" + receiverDefSVR + ", " + newSIK + ")");
            newDelta.combine(g.stringVariableReplicaJoinAt(receiverDefSVR, newSIK));
            System.err.println("[concatM] " + receiverDefSVR + " <- " + g.getAStringFor(receiverDefSVR));
            return newDelta;
        }
        case somethingElseM: {
            System.err.println("[StringMethodCall.process] Whoops!");
            return new GraphDelta(g);
        }
        default: {
            throw new RuntimeException("Unhandled MethodEnum");
        }

        }
    }

    @Override
    public String toString() {
        return "(" + this.result + ", " + this.receiverDef + ") <- " + this.receiverUse + "." + this.invokedMethod
                + "(" + this.arguments + ")";
    }

}
