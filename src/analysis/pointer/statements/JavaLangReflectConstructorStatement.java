package analysis.pointer.statements;

import java.util.List;

import analysis.StringAndReflectiveUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

public class JavaLangReflectConstructorStatement extends PointsToStatement {
    private final static TypeReference JavaLangConstructorTR = TypeReference.findOrCreate(ClassLoaderReference.Application,
                                                                                          "Ljava/lang/reflect/Constructor");
    private final static IMethod JavaLangConstructorNewInstance0 = StringAndReflectiveUtil.getIMethod(JavaLangConstructorTR,
                                                                                                      "newInstance",
                                                                                                      "([Ljava/lang/Object;)Ljava/lang/Object;");

    /*
     * depending on how much you are bothered by tonnes of tiny classes,
     * you could create an enum representing each possible constructor method
     * and have the process method handle each method differently with a giant
     * switch statement. See ClassMethodInvocationStatement for an example of this.
     *
     * You can probably reuse the ReflectiveHAF's `recordReflective` method for invocations
     * of constructors. In general, you may need to enrich the HAF to know more things about your
     * particular analysis.
     *
     * I would really have liked to refactor the entirety of Accrue so that HAFs had
     * a monoidal structure. Then we could combine different analyses.
     *
     * In doing so, you'd definitely want to factor out context and heap context. It's
     * really unfortunate that `InstanceKey` is overloaded to mean both heapcontext and
     * AbstractObject. These ought to be separate ideas, with a heapcontext wrapping around
     * AbstractObjects. Alas.
     *
     * I should also mention that as we create more and more special wrapper instance keys,
     * the proliferation of code that should have been generic but isn't will grow.
     */

    public static boolean isConstructorStaticMethodCall(SSAInvokeInstruction i) {
        // TODO Auto-generated method stub
        return false;
    }

    public static boolean isConstructorSpecialMethodCall(SSAInvokeInstruction i) {
        // TODO Auto-generated method stub
        return false;
    }

    public static boolean isConstructorVirtualMethodCall(SSAInvokeInstruction i) {
        // TODO Auto-generated method stub
        return false;
    }

    public static PointsToStatement makeStaticCall(CallSiteReference callSite, IMethod method,
                                                   ReferenceVariable result, List<ReferenceVariable> actuals) {
        // TODO Auto-generated method stub
        return null;
    }

    public static PointsToStatement makeSpecialCall(CallSiteReference callSite, IMethod method,
                                                    ReferenceVariable result, List<ReferenceVariable> actuals) {
        // TODO Auto-generated method stub
        return null;
    }

    public static PointsToStatement makeVirtualCall(CallSiteReference callSite, IMethod method,
                                                    ReferenceVariable result, List<ReferenceVariable> actuals) {
        // TODO Auto-generated method stub
        return null;
    }

    private JavaLangReflectConstructorStatement(IMethod m) {
        super(m);
        // TODO Auto-generated constructor stub
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String toString() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        // TODO Auto-generated method stub

    }

    @Override
    public List<ReferenceVariable> getUses() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ReferenceVariable getDef() {
        // TODO Auto-generated method stub
        return null;
    }

}
