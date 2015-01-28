package test.flowsenspointer;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import junit.framework.TestCase;
import main.AccrueAnalysisMain;

import org.json.JSONException;
import org.junit.Test;

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
public abstract class TestFlowSensitivePointer extends TestCase {

    // Method to run the test. Should be overridden by RunTestxxx methods which extends this abstract class
    @Test(timeout = 100000)
    public abstract void test() throws ClassHierarchyException, IOException, JSONException;

    // Names of interesting methods
    private static final String MOST_RECENT = "mostRecent";
    private static final String NON_MOST_RECENT = "nonMostRecent";
    private static final String POINTS_TO_NULL = "pointsToNull";
    private static final String NOT_MOST_RECENT = "notMostRecent";
    private static final String NOT_NON_MOST_RECENT = "notNonMostRecent";
    private static final String NOT_NULL = "notNull";
    private static final String POINTS_TO = "pointsTo";
    private static final String POINTS_TO_SIZE = "pointsToSize";

    /**
     * Descriptor for a method with a single java.lang.Object argument and void return
     */
    private static final String VOID_1_ARG_DESCRIPTOR = "(Ljava/lang/Object;)V";
    /**
     * Descriptor for a method with a java.lang.Object and an integer argument and void return
     */
    private static final String VOID_2_ARG_DESCRIPTOR = "(Ljava/lang/Object;I)V";
    /**
     * WALA class loader
     */
    private static final ClassLoaderReference LOADER = ClassLoaderReference.Application;
    /**
     * TypeReference for the base class where the interesting methods are located
     */
    private static final TypeReference TEST_BASE_CLASS = TypeReference.findOrCreate(LOADER, "L"
            + "test/flowsenspointer/TestBaseClass");

    /**
     * Find reference arguments for any call to the given method
     *
     * @param methodName name of method
     * @param g points-to graph to look for method calls in
     * @return set of (points-to graph node, program point) pairs, the first element is the method argument the second
     *         element is the program point at which the call occurs
     */
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
                        if (AnalysisUtil.getClassHierarchy().resolveMethod(inv.getDeclaredTarget()).equals(m)) {
                            int use = i.getUse(0);
                            ReferenceVariable rv = rvCache.getReferenceVariable(use, n.getMethod());
                            ReferenceVariableReplica rvr = new ReferenceVariableReplica(n.getContext(), rv, g.getHaf());
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

    /**
     * Generate a points-to graph using the single-threaded analysis
     *
     * @param className entry point class name
     * @return complete points-to graph
     *
     * @throws ClassHierarchyException
     * @throws IOException
     * @throws JSONException
     */
    private static PointsToGraph generatePointsToGraphSingleThreaded(String className) throws ClassHierarchyException,
                                                                                      IOException, JSONException {
        className = className.replaceAll("/", ".");
        String[] args = new String[] { "-testMode", "-n", "pointsto2", "-singleThreaded", "-e", className,
                "-paranoidPointerAnalysis" };
        AnalysisUtil.TEST_resetAllStaticFields();
        AccrueAnalysisMain.main(args);
        return AccrueAnalysisMain.graph;
    }

    /**
     * Generate a points-to graph using the multi-threaded analysis
     *
     * @param className entry point class name
     * @return complete points-to graph
     *
     * @throws ClassHierarchyException
     * @throws IOException
     * @throws JSONException
     */
    private static PointsToGraph generatePointsToGraphMultiThreaded(String className) throws ClassHierarchyException,
                                                                                     IOException, JSONException {
        String[] args = new String[] { "-testMode", "-n", "pointsto2", "-e", className };
        AnalysisUtil.TEST_resetAllStaticFields();
        AccrueAnalysisMain.main(args);
        return AccrueAnalysisMain.graph;
    }

    /**
     * Find all calls to "mostRecent" and confirm that the argument points to a most-recent object
     *
     * @param g points-to graph
     */
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

    /**
     * Find all calls to "notMostRecent" and confirm that the argument does not point to a most-recent object
     *
     * @param g points-to graph
     */
    private static void findNotMostRecent(PointsToGraph g) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> mostRecent = findArg(NOT_MOST_RECENT, g);
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
            if (foundMostRecent) {
                sb.append("Found a most recent target for " + p + "\n");
                Iterator<? extends InstanceKey> iter2 = g.pointsToIterator(p.fst(), p.snd());
                while (iter2.hasNext()) {
                    InstanceKeyRecency ik = (InstanceKeyRecency) iter2.next();
                    sb.append("\t" + ik + "\n");
                }
            }
            assertTrue(sb.toString(), !foundMostRecent);
        }
    }

    /**
     * Find all calls to "pointsToNull" and confirm that the argument points to "null"
     *
     * @param g points-to graph
     */
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

    /**
     * Find all calls to "notNull" and confirm that the argument does not point to "null"
     *
     * @param g points-to graph
     */
    private static void findNotNull(PointsToGraph g) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> mostRecent = findArg(NOT_NULL, g);
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
            if (foundNull) {
                sb.append("Found null target for " + p + "\n");
                Iterator<? extends InstanceKey> iter2 = g.pointsToIterator(p.fst(), p.snd());
                while (iter2.hasNext()) {
                    InstanceKeyRecency ik = (InstanceKeyRecency) iter2.next();
                    sb.append("\t" + ik + "\n");
                }
            }
            assertTrue(sb.toString(), !foundNull);
        }
    }

    /**
     * Find all calls to "nonMostRecent" and confirm that the argument points to a non-most-recent object
     *
     * @param g points-to graph
     */
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

    /**
     * Find all calls to "notNonMostRecent" and confirm that the argument does not point to a non-most-recent object
     *
     * @param g points-to graph
     */
    private static void findNotNonMostRecent(PointsToGraph g) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> nonMR = findArg(NOT_NON_MOST_RECENT, g);
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
            if (foundNMR) {
                sb.append("Found non-most-recent target for " + p + "\n");
                Iterator<? extends InstanceKey> iter2 = g.pointsToIterator(p.fst(), p.snd());
                while (iter2.hasNext()) {
                    InstanceKeyRecency ik = (InstanceKeyRecency) iter2.next();
                    sb.append("\t" + ik + "\n");
                }
            }
            assertTrue(sb.toString(), !foundNMR);
        }
    }

    /**
     * Find all calls to "pointsToSize" and confirm that the first argument points to the number of elements indicated
     * by the second argument
     *
     * @param g points-to graph
     */
    private static void confirmSize(PointsToGraph g) {
        CallGraph cg = g.getCallGraph();

        MethodReference mr = MethodReference.findOrCreate(TEST_BASE_CLASS, POINTS_TO_SIZE, VOID_2_ARG_DESCRIPTOR);
        IMethod m = AnalysisUtil.getClassHierarchy().resolveMethod(mr);
        if (m == null) {
            System.err.println("TEST: WARNING " + PrettyPrinter.methodString(mr) + " not found");
            return;
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
                        if (AnalysisUtil.getClassHierarchy().resolveMethod(inv.getDeclaredTarget()).equals(m)) {
                            int use = i.getUse(0);
                            ReferenceVariable rv = rvCache.getReferenceVariable(use, n.getMethod());
                            ReferenceVariableReplica rvr = new ReferenceVariableReplica(n.getContext(), rv, g.getHaf());
                            assert insToPP.get(inv) != null : inv + " " + insToPP;
                            assert insToPP.get(inv).getReplica(n.getContext()) != null;
                            InterProgramPointReplica pp = insToPP.get(inv).getReplica(n.getContext()).pre();

                            Iterator<? extends InstanceKey> iter = g.pointsToIterator(rvr, pp);
                            int actualSize = 0;
                            while (iter.hasNext()) {
                                iter.next();
                                actualSize++;
                            }

                            int use2 = i.getUse(1);
                            assert ir.getSymbolTable().isIntegerConstant(use2) : "Second arg not constant for " + inv;
                            int desiredSize = ir.getSymbolTable().getIntValue(use2);
                            if (actualSize != desiredSize) {
                                StringBuilder sb = new StringBuilder();
                                sb.append("TEST: Wrong number of elements in points-to set for " + rvr + "\n");
                                sb.append("TEST: Expected " + desiredSize + " found " + actualSize + "\n");
                                Iterator<? extends InstanceKey> iter2 = g.pointsToIterator(rvr, pp);
                                while (iter2.hasNext()) {
                                    InstanceKeyRecency ik = (InstanceKeyRecency) iter2.next();
                                    sb.append("TEST: \t" + ik + "\n");
                                }
                                System.err.println(sb.toString());
                            }
                            assertEquals(desiredSize, actualSize);
                        }
                    }
                }
            }
        }
    }

    /**
     * Find all calls to "pointsTo" and print out the points-to set for the argument
     *
     * @param g points-to graph
     */
    private static void pointsTo(PointsToGraph g) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> pt;
        pt = findArg(POINTS_TO, g);
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

    /**
     * Check all the test methods for a given test case
     *
     * @param className entry point for the test to run
     * @param multiThreaded whether to use the multi-threaded points-to analysis (or the single-threaded)
     *
     * @throws ClassHierarchyException
     * @throws IOException
     * @throws JSONException
     */
    protected static void runTest(String className, boolean multiThreaded) throws ClassHierarchyException, IOException,
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
        findNotMostRecent(g);
        findNotNonMostRecent(g);
        findNotNull(g);
        confirmSize(g);
        System.err.println("TEST: time = " + (System.currentTimeMillis() - start) / 1000 + "s");
    }
}
