package intent.unit;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import junit.framework.TestCase;
import util.print.CFGWriter;
import analysis.AnalysisUtil;
import analysis.string.StringAnalysisResults;
import analysis.string.StringVariableFactory;
import android.intent.IntentAnalysisResults;
import android.intent.model.AbstractIntent;
import android.intent.model.AbstractURI;
import android.net.Uri;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;

public class TestIntentAnalysis extends TestCase {

    private static final String BAR = "bar";

    public static void runTest(String testname, AbstractIntent expectedResult) throws ClassHierarchyException,
                                                                              IOException {
        AnalysisUtil.init("classes/intent/", testname);
        IMethod main = AnalysisUtil.getOptions().getEntrypoints().iterator().next().getMethod();
        StringVariableFactory factory = new StringVariableFactory();
        StringAnalysisResults stringResults = new StringAnalysisResults(factory);
        IntentAnalysisResults intentResults = new IntentAnalysisResults(stringResults);
        Map<Integer, AbstractIntent> res = intentResults.getResultsForMethod(main);
        CFGWriter.writeToFile(main);
        for (Integer v : res.keySet()) {
            System.err.println(v + " = " + res.get(v));
        }
        AbstractIntent arg = findArgToBar(main, res);
        if (expectedResult != null) {
            assertNotSame("testname: " + testname + " arg == " + arg, AbstractIntent.ANY, arg);
            assertEquals("testname: " + testname + " " + arg + " != " + expectedResult, expectedResult, arg);
        }
        else {
            assertEquals("testname: " + testname + " " + arg + " != " + AbstractIntent.ANY, AbstractIntent.ANY, arg);
        }
    }

    private static AbstractIntent findArgToBar(IMethod main, Map<Integer, AbstractIntent> map) {
        IR ir = AnalysisUtil.getIR(main);
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            for (SSAInstruction i : bb) {
                if (i instanceof SSAInvokeInstruction) {
                    SSAInvokeInstruction inv = (SSAInvokeInstruction) i;
                    if (inv.getDeclaredTarget().getName().toString().equals(BAR)) {
                        int arg = inv.getUse(0);
                        return map.get(arg);
                    }
                }
            }
        }
        return null;
    }

    public static void test() throws ClassHierarchyException, IOException {
        runTest("intent.tests.NoArgConstructor", AbstractIntent.NONE);
        test1();
    }

    public static void test1() throws ClassHierarchyException, IOException {
        AbstractIntent intent = AbstractIntent.NONE;
        AbstractURI uri = AbstractURI.create(Collections.singleton(Uri.EMPTY));
        intent.joinData(uri);
        runTest("intent.tests.EmptyURI", intent);
    }

}
