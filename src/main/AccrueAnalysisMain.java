package main;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.Set;

import org.json.JSONException;

import util.OrderedPair;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.dataflow.interprocedural.bool.BooleanConstantDataFlow;
import analysis.dataflow.interprocedural.bool.BooleanConstantResults;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionInterproceduralDataFlow;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.interprocedural.nonnull.NonNullInterProceduralDataFlow;
import analysis.dataflow.interprocedural.nonnull.NonNullResults;
import analysis.dataflow.interprocedural.pdg.PDGInterproceduralDataFlow;
import analysis.dataflow.interprocedural.pdg.graph.ProgramDependenceGraph;
import analysis.dataflow.interprocedural.reachability.ReachabilityInterProceduralDataFlow;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.pointer.analyses.CrossProduct;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.StaticCallSiteSensitive;
import analysis.pointer.analyses.TypeSensitive;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysisSingleThreaded;
import analysis.pointer.graph.HafCallGraph;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.StatementRegistrationPass;
import analysis.pointer.statements.PointsToStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ssa.IR;

/**
 * Run one of the selected analyses or tests, see usage
 */
public class AccrueAnalysisMain {

    /**
     * Run one of the selected tests
     * 
     * @param args
     *            options and parameters see useage (pass in "-h") for details
     * @throws IOException
     *             file writing issues
     * @throws ClassHierarchyException
     *             issues reading class files
     * @throws JSONException
     *             issues writing JSON file
     */
    public static void main(String[] args) throws IOException, ClassHierarchyException, JSONException {

        AccrueAnalysisOptions options = AccrueAnalysisOptions.getOptions(args);
        if (options.shouldPrintUseage()) {
            System.err.println(AccrueAnalysisOptions.getUseage());
            return;
        }

        String entryPoint = options.getEntryPoint();
        int outputLevel = options.getOutputLevel();
        String analysisName = options.getAnalysisName();
        int fileLevel = options.getFileLevel();
        String classPath = options.getAnalysisClassPath();

        int otherOutputLevel = 0;
        if (outputLevel >= 9) {
            otherOutputLevel = 9;
        }

        String fileName = entryPoint;
        Entrypoint entry;
        IR ir;
        OrderedPair<PointsToGraph, ReferenceVariableCache> results;
        PointsToGraph g;
        ReferenceVariableCache rvCache;
        switch (analysisName) {
        // case "pointsto":
        // AnalysisUtil.init(classPath, entryPoint);
        // results = generatePointsToGraph(outputLevel);
        // g = results.fst();
        // g.dumpPointsToGraphToFile(fileName + "_ptg", false);
        // ((HafCallGraph) g.getCallGraph()).dumpCallGraphToFile(fileName + "_cg", false);
        //
        // System.err.println(g.getNodes().size() + " Nodes");
        // int num = 0;
        // for (PointsToGraphNode n : g.getNodes()) {
        // num += g.getPointsToSet(n).size();
        // }
        // System.err.println(num + " Edges");
        // System.err.println(g.getAllHContexts().size() + " HContexts");
        //
        // int numNodes = 0;
        // for (@SuppressWarnings("unused")
        // CGNode n : g.getCallGraph()) {
        // numNodes++;
        // }
        // System.err.println(numNodes + " CGNodes");
        // break;
        case "pointsto":
            AnalysisUtil.init(classPath, entryPoint);
            results = generatePointsToGraphOnline(outputLevel);
            g = results.fst();
            g.dumpPointsToGraphToFile(fileName + "_ptg", false);
            ((HafCallGraph) g.getCallGraph()).dumpCallGraphToFile(fileName + "_cg", false);

            System.err.println(g.getNodes().size() + " Nodes");
            int num = 0;
            for (PointsToGraphNode n : g.getNodes()) {
                num += g.getPointsToSet(n).size();
            }
            System.err.println(num + " Edges");
            System.err.println(g.getAllHContexts().size() + " HContexts");

            int numNodes = 0;
            for (@SuppressWarnings("unused")
            CGNode n : g.getCallGraph()) {
                numNodes++;
            }
            System.err.println(numNodes + " CGNodes");
            break;
        case "maincfg":
            AnalysisUtil.init(classPath, entryPoint);
            entry = AnalysisUtil.getOptions().getEntrypoints().iterator().next();
            ir = AnalysisUtil.getIR(entry.getMethod());
            printSingleCFG(ir, fileName + "_main");
            break;
        case "bool":
            AnalysisUtil.init(classPath, entryPoint);
            runBooleanConstant(entryPoint, outputLevel);
            break;
        case "nonnull":
            AnalysisUtil.init(classPath, entryPoint);
            results = generatePointsToGraphOnline(otherOutputLevel);
            g = results.fst();
            rvCache = results.snd();
            ReachabilityResults r = runReachability(otherOutputLevel, g, rvCache, null);
            NonNullResults nonNull = runNonNull(outputLevel, g, r, rvCache);
            nonNull.writeAllToFiles(r);
            break;
        case "precise-ex":
            AnalysisUtil.init(classPath, entryPoint);
            results = generatePointsToGraphOnline(otherOutputLevel);
            g = results.fst();
            rvCache = results.snd();
            r = runReachability(otherOutputLevel, g, rvCache, null);
            nonNull = runNonNull(otherOutputLevel, g, r, rvCache);
            PreciseExceptionResults preciseEx = runPreciseExceptions(outputLevel, g, r, nonNull, rvCache);
            preciseEx.writeAllToFiles(r);
            break;
        case "reachability":
            AnalysisUtil.init(classPath, entryPoint);
            results = generatePointsToGraphOnline(otherOutputLevel);
            g = results.fst();
            rvCache = results.snd();
            r = runReachability(outputLevel, g, rvCache, null);
            r.writeAllToFiles();
            break;
        case "cfg":
            AnalysisUtil.init(classPath, entryPoint);
            results = generatePointsToGraphOnline(otherOutputLevel);
            g = results.fst();
            printAllCFG(g);
            break;
        case "pdg":
            AnalysisUtil.init(classPath, entryPoint);
            results = generatePointsToGraphOnline(otherOutputLevel);
            g = results.fst();
            rvCache = results.snd();
            r = runReachability(otherOutputLevel, g, rvCache, null);
            nonNull = runNonNull(otherOutputLevel, g, r, rvCache);
            preciseEx = runPreciseExceptions(otherOutputLevel, g, r, nonNull, rvCache);
            ReachabilityResults r2 = runReachability(otherOutputLevel, g, rvCache, preciseEx);
            // r2.writeAllToFiles();
            ProgramDependenceGraph pdg = runPDG(outputLevel, g, r2, preciseEx, rvCache);
            pdg.printDetailedCounts();
            String fullName = "tests/pdg_" + fileName + ".json";
            FileWriter file = new FileWriter(fullName);
            pdg.writeJSON(file);
            // pdg.writeDot(file, true, 1);
            file.close();
            System.err.println("JSON written to " + fullName);
            if (fileLevel >= 1) {
                printAllCFG(g);
                pdg.intraProcDotToFile(1);
            }

            if (fileLevel >= 2) {
                r.writeAllToFiles();
                nonNull.writeAllToFiles(r);
                preciseEx.writeAllToFiles(r);
            }
            break;
        default:
            assert false;
            throw new RuntimeException("The options parser should prevent reaching this point");
        }
    }

    /**
     * Print the control flow graph for all procedures in the call graph
     * 
     * @param g
     *            points to graph
     */
    private static void printAllCFG(PointsToGraph g) {
        Set<IMethod> printed = new LinkedHashSet<>();
        for (CGNode n : g.getCallGraph()) {
            IMethod m = n.getMethod();
            if (!printed.contains(m)) {
                printed.add(m);
                String prefix = "cfg_";
                if (AnalysisUtil.hasSignature(m)) {
                    prefix += "sig_";
                }
                String fileName = prefix + PrettyPrinter.methodString(m);
                IR ir = AnalysisUtil.getIR(m);
                if (ir != null) {
                    printSingleCFG(ir, fileName);
                } else {
                    System.err.println("No CFG for " + PrettyPrinter.cgNodeString(n) + " it "
                                                    + (m.isNative() ? "was" : "wasn't") + " native");
                }
            }
        }
    }

    /**
     * Generate the full points-to graph, print statistics, and save it to a file.
     * 
     * @param outputLevel
     *            print level
     * @return the resulting points-to graph, and cache of reference variables
     */
    @SuppressWarnings("unused")
    // Use the online analysis
    @Deprecated
    private static OrderedPair<PointsToGraph, ReferenceVariableCache> generatePointsToGraph(int outputLevel) {

        // Gather all the points-to statements
        StatementRegistrationPass pass = new StatementRegistrationPass();
        pass.run();
        StatementRegistrar registrar = pass.getRegistrar();
        ReferenceVariableCache rvCache = pass.getAllLocals();

        // HeapAbstractionFactory haf = new CallSiteSensitive(1);
        // HeapAbstractionFactory haf = new TypeSensitive(2, 1);
        HeapAbstractionFactory haf1 = new TypeSensitive(2, 1);
        HeapAbstractionFactory haf2 = new StaticCallSiteSensitive(2);
        HeapAbstractionFactory haf = new CrossProduct(haf1, haf2);

        PointsToAnalysis analysis = new PointsToAnalysisSingleThreaded(haf);
        PointsToAnalysis.outputLevel = outputLevel;
        PointsToGraph g = analysis.solve(registrar);

        System.err.println("Registered statements: " + pass.getRegistrar().getAllStatements().size());
        if (outputLevel >= 2) {
            for (PointsToStatement s : pass.getRegistrar().getAllStatements()) {
                System.err.println("\t" + s + " (" + s.getClass().getSimpleName() + ")");
            }
        }
        System.err.println(g.getNodes().size() + " PTG nodes.");
        System.err.println(g.getCallGraph().getNumberOfNodes() + " CG nodes.");

        return new OrderedPair<>(g, rvCache);
    }

    /**
     * Generate the full points-to graph, print statistics, and save it to a file.
     * 
     * @param outputLevel
     *            print level
     * @return the resulting points-to graph
     */
    private static OrderedPair<PointsToGraph, ReferenceVariableCache> generatePointsToGraphOnline(int outputLevel) {

        // HeapAbstractionFactory haf = new CallSiteSensitive(1);

        HeapAbstractionFactory haf1 = new TypeSensitive(2, 1);
        HeapAbstractionFactory haf2 = new StaticCallSiteSensitive(2);
        HeapAbstractionFactory haf = new CrossProduct(haf1, haf2);

        // HeapAbstractionFactory haf = new TypeSensitive(2, 1);

        PointsToAnalysisSingleThreaded analysis = new PointsToAnalysisSingleThreaded(haf);
        PointsToAnalysis.outputLevel = outputLevel;
        StatementRegistrar online = new StatementRegistrar();
        PointsToGraph g = analysis.solveAndRegister(online);

        System.err.println("Registered statements: " + online.getAllStatements().size());
        if (outputLevel >= 2) {
            for (PointsToStatement s : online.getAllStatements()) {
                System.err.println("\t" + s + " (" + s.getClass().getSimpleName() + ")");
            }
        }
        System.err.println(g.getNodes().size() + " PTG nodes.");
        System.err.println(g.getCallGraph().getNumberOfNodes() + " CG nodes.");
        System.err.println(g.clinitCount + " Class initializers.");

        ReferenceVariableCache rvCache = online.getAllLocals();
        return new OrderedPair<>(g, rvCache);
    }

    /**
     * Print the control flow graph for the given method
     * 
     * @param IR
     *            code for the method to be printed
     * @param fileName
     *            file to save the results
     */
    private static void printSingleCFG(IR ir, String fileName) {
        CFGWriter cfg = new CFGWriter(ir);
        String dir = "tests";
        String fullFilename = dir + "/" + fileName + ".dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            cfg.writeVerbose(out, "", "\\l");
            System.err.println("DOT written to: " + fullFilename);
        } catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    /**
     * Run the non-null analysis and return the results
     * 
     * @param method
     *            method to print the results for
     * @param g
     *            points-to graph
     * @param r
     *            results of a reachability analysis
     * @return the results of the non-null analysis
     */
    private static NonNullResults runNonNull(int outputLevel, PointsToGraph g, ReachabilityResults r,
                                    ReferenceVariableCache rvCache) {
        NonNullInterProceduralDataFlow analysis = new NonNullInterProceduralDataFlow(g, r, rvCache);
        analysis.setOutputLevel(outputLevel);
        analysis.runAnalysis();
        return analysis.getAnalysisResults();
    }

    /**
     * Run a precise exceptions analysis
     * 
     * @param outputLevel
     *            level of logging
     * @param g
     *            points-to graph
     * @param r
     *            results of a reachability analysis
     * @param nonNull
     *            results of a non-null analysis
     * @return the results of the precise exceptions analysis
     */
    private static PreciseExceptionResults runPreciseExceptions(int outputLevel, PointsToGraph g,
                                    ReachabilityResults r, NonNullResults nonNull, ReferenceVariableCache rvCache) {
        PreciseExceptionInterproceduralDataFlow analysis = new PreciseExceptionInterproceduralDataFlow(g, nonNull, r,
                                        rvCache);
        analysis.setOutputLevel(outputLevel);
        analysis.runAnalysis();
        return analysis.getAnalysisResults();
    }

    /**
     * Run the inter-procedural reachability analysis
     * 
     * @param outputLevel
     *            logging level
     * @param g
     *            points-to graph
     * @param rvCache
     *            cache of points-to analysis reference variables
     * @param preciseEx
     *            results of a precise exceptions analysis or null if none has been run yet
     */
    private static ReachabilityResults runReachability(int outputLevel, PointsToGraph g,
                                    ReferenceVariableCache rvCache, PreciseExceptionResults preciseEx) {
        ReachabilityInterProceduralDataFlow analysis = new ReachabilityInterProceduralDataFlow(g, rvCache, preciseEx);
        analysis.setOutputLevel(outputLevel);
        analysis.runAnalysis();
        return analysis.getAnalysisResults();
    }

    /**
     * Run an inter-procedural analysis that generates a program dependence graph
     * 
     * @param outputLevel
     *            logging level
     * @param g
     *            points-to graph
     * @param r
     *            results of a reachability analysis
     * @param preciseEx
     *            results of a precise exceptions analysis
     * @return the program dependence graph
     */
    private static ProgramDependenceGraph runPDG(int outputLevel, PointsToGraph g, ReachabilityResults r,
                                    PreciseExceptionResults preciseEx, ReferenceVariableCache rvCache) {
        PDGInterproceduralDataFlow analysis = new PDGInterproceduralDataFlow(g, preciseEx, r, rvCache);
        analysis.setOutputLevel(outputLevel);
        analysis.runAnalysis();
        return analysis.getAnalysisResults();
    }

    /**
     * Run the analysis to determine which locals are boolean constants and print the results
     * 
     * @param entryPoint
     *            full name of class to print results for contained methods
     * @param outputLevel
     *            amount of debugging
     */
    private static void runBooleanConstant(String entryPoint, int outputLevel) {
        OrderedPair<PointsToGraph, ReferenceVariableCache> results = generatePointsToGraphOnline(0);
        BooleanConstantDataFlow df = null;
        System.err.println("ENTRY: " + entryPoint);
        for (CGNode n : results.fst().getCallGraph()) {
            if (PrettyPrinter.methodString(n.getMethod()).contains(entryPoint)) {
                System.err.println("Analyzing: " + PrettyPrinter.cgNodeString(n));
                df = new BooleanConstantDataFlow(n, results.fst(), results.snd());
                BooleanConstantResults r = df.run();
                r.writeResultsToFile();

                if (outputLevel >= 1) {
                    CFGWriter.writeToFile(n.getIR());
                }
            }
        }

        if (df == null) {
            System.err.println("Could not find methods in: " + entryPoint);
            return;
        }
    }
}
