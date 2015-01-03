package test.flowsenspointer;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import junit.framework.AssertionFailedError;
import junit.framework.TestCase;
import main.AccrueAnalysisMain;

import org.json.JSONException;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * <ol>
 * <li>Support repeatable testing of flow-sensitive points-to analysis results</li>
 * <li>Special method names in tests tell the test framework what to look for</li>
 * <ul>
 * <li>nonMostRecent - the argument points to a non-most-recent instance key (and possible other IKs)</li>
 * <li>mostRecent - the argument points to a most-recent instance key (and possibly other IKs)</li>
 * <li>pointsToNull - the argument points to null (and possibly other IKs)</li>
 * <li>pointsTo - print the points-to set for the argument (for debugging)</li>
 * </ul>
 * </ol>
 */
public class TestFlowSensitivePointer extends TestCase {

    public static void testLoad() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/Load", false);
    }

    public static void testLoadMT() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/Load", true);
    }

    public static void testLoad2() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/Load2", false);
    }

    public static void testLoad2MT() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/Load2", true);
    }

    public static void testMove() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/Move", false);
    }

    public static void testMoveMT() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/Move", true);
    }

    public static void testNew() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/New", false);
    }

    public static void testNewMT() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/New", true);
    }

    public static void testPreserve() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/Preserve", false);
    }

    public static void testPreserveMT() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/Preserve", true);
    }

    public static void testSCall() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/SCall", false);
    }

    public static void testSCallMT() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/SCall", true);
    }

    public static void testStore() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/Store", false);
    }

    public static void testStoreMT() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/Store", true);
    }

    public static void testStrongUpdate() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/StrongUpdate", false);
    }

    public static void testStrongUpdateMT() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/StrongUpdate", true);
    }

    public static void testVCall() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/VCall", false);
    }

    public static void testVCallMT() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/VCall", true);
    }

    public static void testVCall2() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/VCall2", false);
    }

    public static void testVCall2MT() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/VCall2", true);
    }

    private static final String MOST_RECENT = "mostRecent";
    private static final String NON_MOST_RECENT = "nonMostRecent";
    private static final String POINTS_TO_NULL = "pointsToNull";
    private static final String POINTS_TO = "pointsTo";
    private static final String VOID_1_ARG_DESCRIPTOR = "(Ljava/lang/Object;)V";
    private static final ClassLoaderReference LOADER = ClassLoaderReference.Application;
    private static final TypeReference TEST_BASE_CLASS = TypeReference.findOrCreate(LOADER, "L"
            + "test/flowsenspointer/TestBaseClass");

    private static Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> findArg(String methodName,
                                                                                                PointsToGraph g) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> args = new LinkedHashSet<>();
        CallGraph cg = g.getCallGraph();

        MethodReference mr = MethodReference.findOrCreate(TEST_BASE_CLASS, methodName, VOID_1_ARG_DESCRIPTOR);
        IMethod m = AnalysisUtil.getClassHierarchy().resolveMethod(mr);
        if (m == null) {
            System.err.println("TEST: WARNING " + PrettyPrinter.methodString(mr) + " not found");
            return Collections.emptySet();
        }

        Map<SSAInstruction, ProgramPoint> insToPP = g.getRegistrar().getInsToPP();
        ReferenceVariableCache rvCache = g.getRegistrar().getRvCache();
        for (CGNode n : cg) {
            IR ir = AnalysisUtil.getIR(n.getMethod());
            if (ir == null) {
                assert n.getMethod().isNative() : "No IR for non-native method "
                        + PrettyPrinter.methodString(n.getMethod());
                continue;
            }
            for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
                for (SSAInstruction i : bb) {
                    if (i instanceof SSAInvokeInstruction) {
                        SSAInvokeInstruction inv = (SSAInvokeInstruction) i;
                        if (inv.getDeclaredTarget().equals(mr)) {
                            int use = i.getUse(0);
                            ReferenceVariable rv = rvCache.getReferenceVariable(use, n.getMethod());
                            ReferenceVariableReplica rvr = new ReferenceVariableReplica(n.getContext(), rv, g.getHaf());
                            if (insToPP.get(inv) == null) {
                                System.err.println(inv);
                                for (SSAInstruction ins : insToPP.keySet()) {
                                    System.err.println("\t" + ins + " XXXXX "
                                            + insToPP.get(ins).getContainingProcedure());
                                }
                            }
                            assert insToPP.get(inv) != null : inv + " " + insToPP;
                            assert insToPP.get(inv).getReplica(n.getContext()) != null;
                            args.add(new OrderedPair<>(rvr, insToPP.get(inv).getReplica(n.getContext()).pre()));
                        }
                    }
                }
            }
        }
        return args;
    }

    private static PointsToGraph generatePointsToGraphSingleThreaded(String className) throws ClassHierarchyException,
                                                                                      IOException, JSONException {
        className = className.replaceAll("/", ".");
        String[] args = new String[] { "-testMode", "-n", "pointsto2", "-singleThreaded", "-simplePrint", "-e",
                className, "-out", "tests", "-paranoidPointerAnalysis" };
        AnalysisUtil.TEST_resetAllStaticFields();
        AccrueAnalysisMain.main(args);
        return AccrueAnalysisMain.graph;
    }

    private static PointsToGraph generatePointsToGraphMultiThreaded(String className) throws ClassHierarchyException,
                                                                                     IOException, JSONException {
        String[] args = new String[] { "-testMode", "-n", "pointsto2", "-e", className };
        AnalysisUtil.TEST_resetAllStaticFields();
        AccrueAnalysisMain.main(args);
        return AccrueAnalysisMain.graph;
    }

    private static void findMostRecent(PointsToGraph g) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> mostRecent = findArg(MOST_RECENT, g);
        for (OrderedPair<ReferenceVariableReplica, InterProgramPointReplica> p : mostRecent) {
            boolean foundMostRecent = false;
            Iterator<? extends InstanceKey> iter = g.pointsToIterator(p.fst(), p.snd());
            assertTrue("empty points-to set for " + p, iter.hasNext());
            while (iter.hasNext()) {
                InstanceKeyRecency ik = (InstanceKeyRecency) iter.next();
                if (ik.isRecent() && ik.isTrackingMostRecent()) {
                    foundMostRecent = true;
                }
            }

            StringBuilder sb = new StringBuilder();
            if (!foundMostRecent) {
                sb.append("Did not find a most recent target for " + p + "\n");
                Iterator<? extends InstanceKey> iter2 = g.pointsToIterator(p.fst(), p.snd());
                while (iter2.hasNext()) {
                    InstanceKeyRecency ik = (InstanceKeyRecency) iter2.next();
                    sb.append("\t" + ik + "\n");
                }
            }
            assertTrue(sb.toString(), foundMostRecent);
        }
    }

    private static void findPointsToNull(PointsToGraph g) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> mostRecent = findArg(POINTS_TO_NULL, g);
        for (OrderedPair<ReferenceVariableReplica, InterProgramPointReplica> p : mostRecent) {
            boolean foundNull = false;
            Iterator<? extends InstanceKey> iter = g.pointsToIterator(p.fst(), p.snd());
            assertTrue("empty points-to set for " + p, iter.hasNext());
            while (iter.hasNext()) {
                InstanceKeyRecency ik = (InstanceKeyRecency) iter.next();
                if (g.isNullInstanceKey(ik)) {
                    foundNull = true;
                }
            }

            StringBuilder sb = new StringBuilder();
            if (!foundNull) {
                sb.append("Did not find null target for " + p + "\n");
                Iterator<? extends InstanceKey> iter2 = g.pointsToIterator(p.fst(), p.snd());
                while (iter2.hasNext()) {
                    InstanceKeyRecency ik = (InstanceKeyRecency) iter2.next();
                    sb.append("\t" + ik + "\n");
                }
            }
            assertTrue(sb.toString(), foundNull);
        }
    }

    private static void findNonMostRecent(PointsToGraph g) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> nonMR = findArg(NON_MOST_RECENT, g);
        for (OrderedPair<ReferenceVariableReplica, InterProgramPointReplica> p : nonMR) {
            boolean foundNMR = false;
            Iterator<? extends InstanceKey> iter = g.pointsToIterator(p.fst(), p.snd());
            assertTrue("empty points-to set for " + p, iter.hasNext());
            while (iter.hasNext()) {
                InstanceKeyRecency ik = (InstanceKeyRecency) iter.next();
                if (!ik.isRecent()) {
                    foundNMR = true;
                }
            }

            StringBuilder sb = new StringBuilder();
            if (!foundNMR) {
                sb.append("Did not find non-most-recent target for " + p + "\n");
                Iterator<? extends InstanceKey> iter2 = g.pointsToIterator(p.fst(), p.snd());
                while (iter2.hasNext()) {
                    InstanceKeyRecency ik = (InstanceKeyRecency) iter2.next();
                    sb.append("\t" + ik + "\n");
                }
            }
            assertTrue(sb.toString(), foundNMR);
        }
    }

    private static void pointsTo(PointsToGraph g) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> pt;
        try {
            pt = findArg(POINTS_TO, g);
        }
        catch (AssertionFailedError e) {
            System.err.println("TEST: No points-to sets requested.");
            return;
        }
        for (OrderedPair<ReferenceVariableReplica, InterProgramPointReplica> p : pt) {
            Iterator<? extends InstanceKey> iter = g.pointsToIterator(p.fst(), p.snd());
            System.err.println("TEST: POINTS TO");
            System.err.println("TEST:   Node: " + p.fst());
            System.err.println("TEST:   PP:   " + p.snd());
            System.err.println("TEST:   Points-to set:  ");
            if (!iter.hasNext()) {
                System.err.println("TEST:   EMPTY");
            }
            while (iter.hasNext()) {
                InstanceKeyRecency ik = (InstanceKeyRecency) iter.next();
                System.err.println("TEST:      " + ik);
            }
        }
    }

    private static void runTest(String className, boolean multiThreaded) throws ClassHierarchyException, IOException,
                                                                        JSONException {
        long start = System.currentTimeMillis();
        System.err.println("TEST: \nTEST: \nTEST: %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
        PointsToGraph g;
        if (multiThreaded) {
            System.err.println("TEST: %%%\t" + className + " multi threaded");
            System.err.println("TEST: %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\nTEST: ");
            g = generatePointsToGraphMultiThreaded(className);
        }
        else {
            System.err.println("TEST: %%%\t" + className + " single threaded");
            System.err.println("TEST: %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\nTEST: ");
            g = generatePointsToGraphSingleThreaded(className);
        }

        pointsTo(g);
        findMostRecent(g);
        findNonMostRecent(g);
        findPointsToNull(g);
        System.err.println("TEST: time = " + (System.currentTimeMillis() - start) / 1000 + "s");
    }
}
