package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.List;

import util.optional.Optional;
import analysis.AnalysisUtil;
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
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.strings.Atom;

public class StringMethodCall extends StringStatement {
    public final static TypeReference JavaLangStringTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Primordial,
                                                                                               TypeName.string2TypeName("Ljava/lang/String"));
    public final static Atom concatAtom = Atom.findOrCreateUnicodeAtom("concat");
    public final static Descriptor concatDesc = Descriptor.findOrCreateUTF8(Language.JAVA,
                                                                            "(Ljava/lang/String;)Ljava/lang/String;");
    public final static MethodReference JavaLangStringConcat = MethodReference.findOrCreate(JavaLangStringTypeReference,
                                                                                            concatAtom,
                                                                                            concatDesc);
    public final static MethodReference JavaLangStringInit = MethodReference.findOrCreate(JavaLangStringTypeReference,
                                                                                          MethodReference.initSelector);

    public final static IMethod JavaLangStringConcatIMethod = AnalysisUtil.getClassHierarchy()
                                                                          .resolveMethod(JavaLangStringConcat);
    public final static IMethod JavaLangStringInitIMethod = AnalysisUtil.getClassHierarchy()
                                                                        .resolveMethod(JavaLangStringInit);
    public final static TypeReference JavaLangStringBuilderTypeReference = TypeReference.findOrCreate(ClassLoaderReference.Primordial,
                                                                                                      TypeName.string2TypeName("Ljava/lang/StringBuilder"));
    public final static Atom appendStringAtom = Atom.findOrCreateUnicodeAtom("append");
    public final static Descriptor appendStringDesc = Descriptor.findOrCreateUTF8(Language.JAVA,
                                                                                  "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
    public final static MethodReference JavaLangStringBuilderAppendString = MethodReference.findOrCreate(JavaLangStringBuilderTypeReference,
                                                                                                         appendStringAtom,
                                                                                                         appendStringDesc);
    public final static IMethod JavaLangStringBuilderAppendStringIMethod = AnalysisUtil.getClassHierarchy()
                                                                                       .resolveMethod(JavaLangStringBuilderAppendString);

    public final static Atom appendStringBuilderAtom = Atom.findOrCreateUnicodeAtom("append");
    public final static Descriptor appendStringBuilderDesc = Descriptor.findOrCreateUTF8(Language.JAVA,
                                                                                         "(Ljava/lang/StringBuilder;)Ljava/lang/StringBuilder;");
    public final static MethodReference JavaLangStringBuilderAppendStringBuilder = MethodReference.findOrCreate(JavaLangStringBuilderTypeReference,
                                                                                                                appendStringBuilderAtom,
                                                                                                                appendStringBuilderDesc);
    public final static IMethod JavaLangStringBuilderAppendStringBuilderIMethod = AnalysisUtil.getClassHierarchy()
                                                                                              .resolveMethod(JavaLangStringBuilderAppendStringBuilder);
    private static final int MAX_STRING_SET_SIZE = 5;

    private final CallSiteReference callSite;
    private final MethodEnum invokedMethod;
    private final IMethod invokingMethod;
    private final StringVariable result;
    private StringVariable receiver;
    private final List<StringVariable> arguments;
    private final FlowSensitiveStringVariableFactory stringVariableFactory;

    private enum MethodEnum {
        concatM, somethingElseM
    }

    private static MethodEnum imethodToMethodEnum(IMethod m) {
        if (m.equals(JavaLangStringConcatIMethod) || m.equals(JavaLangStringBuilderAppendStringBuilderIMethod)
                || m.equals(JavaLangStringBuilderAppendStringIMethod)) {
            return MethodEnum.concatM;
        }
        else {
            return MethodEnum.somethingElseM;
        }
    }

    public StringMethodCall(CallSiteReference callSite, IMethod method, MethodReference declaredTarget,
                            StringVariable svresult, StringVariable svreceiver, List<StringVariable> svarguments,
                            FlowSensitiveStringVariableFactory stringVariableFactory) {
        super(method);
        this.callSite = callSite;
        this.invokedMethod = imethodToMethodEnum(AnalysisUtil.getClassHierarchy().resolveMethod(declaredTarget));
        this.invokingMethod = method;
        this.result = svresult;
        this.receiver = svreceiver;
        this.arguments = svarguments;
        this.stringVariableFactory = stringVariableFactory;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        StringVariableReplica resultSVR = new StringVariableReplica(context, this.result);
        StringVariableReplica receiverSVR = new StringVariableReplica(context, this.receiver);
//        List<StringVariableReplica> argumentRVRs = this.arguments.stream()
//                                                                 .map(a -> new StringVariableReplica(context, a, haf))
//                                                                 .collect(Collectors.toList());
        List<StringVariableReplica> argumentSVRs = new ArrayList<>();
        for(StringVariable argument : this.arguments) {
            argumentSVRs.add(new StringVariableReplica(context, argument));
        }
        PointsToIterable pti = delta == null ? g : delta;

        switch (this.invokedMethod) {
        case concatM: {
            GraphDelta newDelta = new GraphDelta(g);

            // the first argument is a copy of the "this" argument
            assert argumentSVRs.size() == 2 : argumentSVRs.size();

            Optional<AString> maybeReceiverAString = pti.getAStringFor(receiverSVR);
            Optional<AString> maybeArgumentAString = pti.getAStringFor(argumentSVRs.get(1));

            if (maybeReceiverAString.isNone() || maybeArgumentAString.isNone()) {
                System.err.println("[concatM] g.stringVariableReplicaJoinAt(" + resultSVR + ", AString.makeStringTop(" + MAX_STRING_SET_SIZE + "))");
                newDelta.combine(g.stringVariableReplicaJoinAt(resultSVR, AString.makeStringTop(MAX_STRING_SET_SIZE)));
            } else {
                AString newSIK = maybeReceiverAString.get().concat(maybeArgumentAString.get());
                System.err.println("[concatM] g.stringVariableReplicaJoinAt(" + resultSVR + ", " + newSIK + ")");
                newDelta.combine(g.stringVariableReplicaJoinAt(resultSVR, newSIK));
            }
            return newDelta;
        }
        case somethingElseM: {
            return new GraphDelta(g);
        }
        default: {
            throw new RuntimeException("Unhandled MethodEnum");
        }

        }
    }

    @Override
    public String toString() {
        return this.result + " = " + this.receiver + "." + this.invokedMethod + "(" + this.arguments + ")";
    }

}