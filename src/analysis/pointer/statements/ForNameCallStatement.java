package analysis.pointer.statements;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import util.FiniteSet;
import util.optional.Optional;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.analyses.StringOrProperty;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToIterable;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.strings.StringLikeLocationReplica;
import analysis.pointer.graph.strings.StringLikeVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringLikeVariable;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

public class ForNameCallStatement extends PointsToStatement {

    static final IClass JavaLangClassIClass = AnalysisUtil.getClassHierarchy().lookupClass(TypeReference.JavaLangClass);
    public final static MethodReference JavaLangClassForName = MethodReference.JavaLangClassForName;
    public final static IMethod JavaLangClassForNameIMethod = AnalysisUtil.getClassHierarchy()
                                                                          .resolveMethod(JavaLangClassForName);

    private final CallSiteReference callSite;
    private final IMethod caller;
    private final MethodReference callee;
    private final ReferenceVariable result;
    private final List<StringLikeVariable> actuals;

    public static boolean isForNameCall(SSAInvokeInstruction i) {
        IMethod m = AnalysisUtil.getClassHierarchy().resolveMethod(i.getDeclaredTarget());
        return m.equals(JavaLangClassForNameIMethod);
    }

    public ForNameCallStatement(CallSiteReference callSite, IMethod caller, MethodReference callee,
                                ReferenceVariable result, List<StringLikeVariable> actuals) {
        super(caller);
        this.callSite = callSite;
        this.caller = caller;
        this.callee = callee;
        this.result = result;
        this.actuals = actuals;
        assert actuals.size() == 1 || actuals.size() == 3 : "actuals " + actuals + ", size: " + actuals.size();
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        ReferenceVariableReplica resultRVR = new ReferenceVariableReplica(context, this.result, haf);
        StringLikeVariableReplica nameSVR = new StringLikeVariableReplica(context, this.actuals.get(0));

        GraphDelta changed = new GraphDelta(g);

        g.recordStringStatementUseDependency(nameSVR, originator);
        // Logger.push(true);
        debugln("[ForNameCallStatement] ___________________________");
        debugln("[ForNameCallStatement] in method: " + this.getMethod());
        debugln("[ForNameCallStatement] recording a dependency on " + nameSVR);
        //g.printSVRDependencyTree(nameSVR);

        changed.combine(g.activateStringSolutionVariable(nameSVR));

        PointsToIterable pti = delta == null ? g : delta;

        for (StringLikeLocationReplica nameLocation : nameSVR.getStringLocations()) {
            AString namehat = g.getAStringFor(nameLocation);

            debugln("[ForNameCallStatement] reaching class names are " + namehat);

            AllocSiteNode asn = AllocSiteNodeFactory.createGenerated("forName", JavaLangClassIClass, caller, null, // XXX: I'm duplicating existing forName calls
                                                                     false);
            FiniteSet<IClass> classes;
            if (namehat.isTop()) {
                classes = ((ReflectiveHAF) haf).getAClassTop();
            }
            else if (namehat.isBottom()) {
                classes = ((ReflectiveHAF) haf).getAClassBottom();
            }
            else {
                Set<IClass> classSet = new HashSet<>();
                for (StringOrProperty sop : namehat.getStrings()) {
                    Optional<IClass> maybeIClass = sop.toIClass();
                    if (maybeIClass.isSome()) {
                        classSet.add(maybeIClass.get());
                    }
                }
                classes = ((ReflectiveHAF) haf).getAClassSet(classSet);
            }

            debugln("[ForNameCallStatement] Reflective allocation: classes: " + classes);
            changed.combine(g.addEdge(resultRVR, ((ReflectiveHAF) haf).recordReflective(classes, asn, context)));
            // Logger.pop();
        }
        return changed;
    }

    private static void debugln(String s) {
        System.err.println("[" + Thread.currentThread().getId() + "]" + s);
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
