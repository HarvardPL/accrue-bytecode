package test.flowsenspointer;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import junit.framework.TestCase;
import main.AccrueAnalysisMain;

import org.json.JSONException;

import util.OrderedPair;
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

    private static final String MOST_RECENT = "mostRecent";
    private static final String NON_MOST_RECENT = "nonMostRecent";
    private static final String POINTS_TO_NULL = "pointsToNull";
    private static final String POINTS_TO = "pointsTo";
    private static final String VOID_1_ARG_DESCRIPTOR = "(Ljava/lang/Object;)V";
    private static final ClassLoaderReference LOADER = ClassLoaderReference.Application;

    private static Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> findArg(String className,
                                                                                                String methodName,
                                                                                                PointsToGraph g) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> args = new LinkedHashSet<>();
        CallGraph cg = g.getCallGraph();

        TypeReference tr = TypeReference.findOrCreate(LOADER, "L" + className);
        MethodReference mr = MethodReference.findOrCreate(tr, methodName, VOID_1_ARG_DESCRIPTOR);
        IMethod m = AnalysisUtil.getClassHierarchy().resolveMethod(mr);
        assertTrue("No method found for " + mr, m != null);

        Map<SSAInstruction, ProgramPoint> insToPP = g.getRegistrar().getInsToPP();
        ReferenceVariableCache rvCache = g.getRegistrar().getRvCache();
        for (CGNode n : cg) {
            IR ir = AnalysisUtil.getIR(n.getMethod());
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
                className, "-out", "tests" };
        AccrueAnalysisMain.main(args);
        return AccrueAnalysisMain.graph;
    }

    private static PointsToGraph generatePointsToGraphMultiThreaded(String className) throws ClassHierarchyException,
                                                                                     IOException, JSONException {
        String[] args = new String[] { "-testMode", "-n", "pointsto2", "-e", className };
        AccrueAnalysisMain.main(args);
        return AccrueAnalysisMain.graph;
    }

    private static void findMostRecent(PointsToGraph g, String className) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> mostRecent = findArg(className,
                                                                                                  MOST_RECENT,
                                                                                                  g);
        assertFalse("No arguments found for " + MOST_RECENT + " when testing " + className, mostRecent.isEmpty());
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

    private static void findPointsToNull(PointsToGraph g, String className) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> mostRecent = findArg(className,
                                                                                                  POINTS_TO_NULL,
                                                                                                  g);
        assertFalse("No arguments found for " + POINTS_TO_NULL + " when testing " + className, mostRecent.isEmpty());
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

    private static void findNonMostRecent(PointsToGraph g, String className) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> nonMR = findArg(className,
                                                                                             NON_MOST_RECENT,
                                                                                             g);
        assertFalse("No arguments found for " + NON_MOST_RECENT + " when testing " + className, nonMR.isEmpty());
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

    private static void pointsTo(PointsToGraph g, String className) {
        Set<OrderedPair<ReferenceVariableReplica, InterProgramPointReplica>> pt = findArg(className, POINTS_TO, g);
        assertFalse("No arguments found for " + POINTS_TO + " when testing " + className, pt.isEmpty());
        for (OrderedPair<ReferenceVariableReplica, InterProgramPointReplica> p : pt) {
            Iterator<? extends InstanceKey> iter = g.pointsToIterator(p.fst(), p.snd());
            System.err.println("$$$ POINTS TO");
            System.err.println("\t" + p.fst());
            System.err.println("\t" + p.snd());
            if (!iter.hasNext()) {
                System.err.println("\t\tEMPTY");
            }
            while (iter.hasNext()) {
                InstanceKeyRecency ik = (InstanceKeyRecency) iter.next();
                System.err.println("\t\t" + ik);
            }
        }
    }

    //    public static void testLoad() throws ClassHierarchyException, IOException, JSONException {
    //        AnalysisUtil.TEST_resetAllStaticFields();
    //        String className = "test/flowsenspointer/Load";
    //        PointsToGraph g = generatePointsToGraphSingleThreaded(className);
    //        findMostRecent(g, className);
    //        // All are most recent for this test
    //        // findNonMostRecent(g, className);
    //
    //        AnalysisUtil.TEST_resetAllStaticFields();
    //        g = generatePointsToGraphMultiThreaded(className);
    //        findMostRecent(g, className);
    //        // All are most recent for this test
    //        // findNonMostRecent(g, className);
    //    }

    public static void testLoad2() throws ClassHierarchyException, IOException, JSONException {
        AnalysisUtil.TEST_resetAllStaticFields();
        String className = "test/flowsenspointer/Load2";
        PointsToGraph g = generatePointsToGraphSingleThreaded(className);
        //        findMostRecent(g, className);
        pointsTo(g, className);
        findNonMostRecent(g, className);

        //        findPointsToNull(g, className);

        AnalysisUtil.TEST_resetAllStaticFields();
        g = generatePointsToGraphMultiThreaded(className);
        findMostRecent(g, className);
        findNonMostRecent(g, className);
        findPointsToNull(g, className);
    }

}
