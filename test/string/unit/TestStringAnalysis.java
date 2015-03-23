package string.unit;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import junit.framework.TestCase;
import util.print.CFGWriter;
import analysis.AnalysisUtil;
import analysis.string.AbstractString;
import analysis.string.StringAnalysisResults;
import analysis.string.StringVariableFactory;
import analysis.string.StringVariableFactory.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;

public class TestStringAnalysis extends TestCase {

    private static final String BAR = "bar";

    public static void runTest(String testname, String[] expectedResult) throws ClassHierarchyException, IOException {
        AnalysisUtil.init("classes/string/", testname, "tests/", Runtime.getRuntime().availableProcessors());
        StringVariableFactory factory = new StringVariableFactory();
        StringAnalysisResults stringResults = new StringAnalysisResults(factory);
        IMethod main = AnalysisUtil.getOptions().getEntrypoints().iterator().next().getMethod();
        Map<StringVariable, AbstractString> res = stringResults.getResultsForMethod(main);
        CFGWriter.writeToFile(main);
        for (StringVariable v : res.keySet()) {
            System.err.println(v + " = " + res.get(v));
        }
        AbstractString arg = findArgToBar(main, res, factory);
        if (expectedResult != null) {
            Set<String> expected = new LinkedHashSet<>(Arrays.asList(expectedResult));
            assertNotSame("testname: " + testname + " arg == " + arg, AbstractString.ANY, arg);
            assertEquals("testname: " + testname + " " + arg + " != " + expected, expected, arg.getPossibleValues());
        }
        else {
            assertEquals("testname: " + testname + " " + arg + " != " + AbstractString.ANY, AbstractString.ANY, arg);
        }
    }

    private static AbstractString findArgToBar(IMethod main, Map<StringVariable, AbstractString> map,
                                               StringVariableFactory factory) {
        IR ir = AnalysisUtil.getIR(main);
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            for (SSAInstruction i : bb) {
                if (i instanceof SSAInvokeInstruction) {
                    SSAInvokeInstruction inv = (SSAInvokeInstruction) i;
                    if (inv.getDeclaredTarget().getName().toString().equals(BAR)) {
                        int arg = inv.getUse(0);
                        return map.get(factory.getLocal(arg, main));
                    }
                }
            }
        }
        return null;
    }

    public static void test() throws ClassHierarchyException, IOException {
        runTest("string.tests.Literal", new String[] { "foo" });
        runTest("string.tests.ArrayLoadString", null);
        runTest("string.tests.CheckCastString", new String[] { "foo" });
        runTest("string.tests.PhiString", new String[] { "foo1", "foo2" });
        runTest("string.tests.GetFieldString", null);
        runTest("string.tests.GetStaticString", null);
        runTest("string.tests.GetStaticFinalString", new String[] { "foo" });
        runTest("string.tests.StringInitString", new String[] { "foo" });
        runTest("string.tests.StringInitStringBuilder", new String[] { "foo" });
        runTest("string.tests.StringInitEmpty", new String[] { "" });
        runTest("string.tests.StringInitOther", null);
        runTest("string.tests.StringToString", new String[] { "foo" });
        runTest("string.tests.StringValueOfString", new String[] { "foo" });
        runTest("string.tests.StringValueOfOther", null);
        runTest("string.tests.GetStaticStringBuilder", null);
        runTest("string.tests.GetStringBuilder", null);
        runTest("string.tests.PhiStringBuilder", new String[] { "foo1", "foo2" });
        runTest("string.tests.ArrayLoadStringBuilder", null);
        runTest("string.tests.CheckCastStringBuilder", new String[] { "foo" });
        runTest("string.tests.StringBuilderInitString", new String[] { "foo" });
        runTest("string.tests.StringBuilderInitInt", new String[] { "" });
        runTest("string.tests.StringBuilderInitCharSequence", null);
        runTest("string.tests.StringBuilderAppend", new String[] { "foobar", "foobaz" });

    }

    public static void test1() throws ClassHierarchyException, IOException {
        runTest("string.tests.StringBuilderAppend", new String[] { "foobar", "foobaz" });
    }

}
