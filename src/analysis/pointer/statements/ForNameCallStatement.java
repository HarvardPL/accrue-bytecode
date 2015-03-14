package analysis.pointer.statements;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import util.FiniteSet;
import util.optional.Optional;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.ClassInstanceKey;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.StringVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

public class ForNameCallStatement extends PointsToStatement {

    static final IClass JavaLangClassIClass = AnalysisUtil.getClassHierarchy().lookupClass(TypeReference.JavaLangClass);
    public final static MethodReference JavaLangClassForName = MethodReference.JavaLangClassForName;
    public final static IMethod JavaLangClassForNameIMethod = AnalysisUtil.getClassHierarchy()
                                                                          .resolveMethod(JavaLangClassForName);
    private static final int MAX_STRING_SET_SIZE = 5;


    private final CallSiteReference callSite;
    private final IMethod caller;
    private final MethodReference callee;
    private final ReferenceVariable result;
    private final List<StringVariable> actuals;

    public static boolean isForNameCall(SSAInvokeInstruction i) {
        IMethod m = AnalysisUtil.getClassHierarchy().resolveMethod(i.getDeclaredTarget());
        System.err.println("Comparing " + m + " to " + JavaLangClassForNameIMethod);
        return m.equals(JavaLangClassForNameIMethod);
    }

    public ForNameCallStatement(CallSiteReference callSite, IMethod caller, MethodReference callee,
                                ReferenceVariable result, List<StringVariable> actuals) {
        super(caller);
        this.callSite = callSite;
        this.caller = caller;
        this.callee = callee;
        this.result = result;
        this.actuals = actuals;
        assert actuals.size() == 1 || actuals.size() == 3 : "actuals " + actuals + ", size: " + actuals.size();
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                  GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        ReferenceVariableReplica resultRVR = new ReferenceVariableReplica(context, this.result, haf);
        StringVariableReplica nameSVR = new StringVariableReplica(context, this.actuals.get(0));

        g.recordStringDependency(nameSVR, originator);
        System.err.println("[ForNameCallStatement] recording a dependency on " + nameSVR);

        GraphDelta changed = new GraphDelta(g);
        PointsToIterable pti = delta == null ? g : delta;
        Optional<AString> nameSIK = pti.getAStringUpdatesFor(nameSVR);

        //        Optional<Set<IClass>> classes = nameSIK.getStrings()
        //                .map(stringSet -> stringSet.stream()
        //                                           .map(string -> stringToIClass(string))
        //                                           .flatMap(maybeIC -> maybeIC.map(ic -> Stream.of(ic))
        //                                                                      .orElse(Stream.empty()))
        //                                           .collect(Collectors.toSet()));

        if (nameSIK.isNone()) {
            // XXX: What should we do if there's no known strings?
            System.err.println("[ForNameCallStatement] There are no known strings for " + nameSVR);
        }
        else {
            FiniteSet<String> strings = nameSIK.get().getFiniteStringSet();
            FiniteSet<IClass> classes;
            System.err.println("[ForNameCallStatement] reaching strings are " + strings);
            if (strings.isTop()) {
                classes = FiniteSet.makeTop(MAX_STRING_SET_SIZE);
            }
            else {
                Set<IClass> classSet = new HashSet<>();
                for (String string : strings.getSet()) {
                    Optional<IClass> maybeIClass = stringToIClass(string);
                    if (maybeIClass.isSome()) {
                        classSet.add(maybeIClass.get());
                    }
                }
                classes = FiniteSet.makeFiniteSet(MAX_STRING_SET_SIZE, classSet);
            }

            AllocSiteNode asn = AllocSiteNodeFactory.createGenerated("forName",
                                                                     JavaLangClassIClass,
                                                                     caller,
                                                                     result,
                                                                     false);
            System.err.println("[ForNameCallStatement] Reflective allocation: classes: " + classes);
            changed.combine(g.addEdge(resultRVR, ClassInstanceKey.make(classes)));
        }
        return changed;
    }

    private static Optional<IClass> stringToIClass(String string) {
        IClass result = AnalysisUtil.getClassHierarchy()
                                    .lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, "L"
                                            + string.replace(".", "/")));
        if (result == null) {
            System.err.println("Could not find class for: " + string);
            return Optional.none();
        }
        else {
            return Optional.some(result);
        }
    }

    @Override
    public String toString() {
        return this.result + " = forName(" + this.actuals + ")";
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        throw new RuntimeException("Don't do this! I cannot guarantee that you're giving me a StringVariable");
        //        if (useNumber == 0) {
        //            this.svreceiver = newVariable;
        //        } else {
        //            this.svarguments.set(useNumber - 1, newVariable);
        //        }
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.emptyList();
        //        throw new RuntimeException("No reference variable uses, don't call this method");
        //        List<ReferenceVariable> uses = new ArrayList<>();
        //        uses.addAll(actuals);
        //        return uses;
    }

    @Override
    public ReferenceVariable getDef() {
        return this.result;
    }

}