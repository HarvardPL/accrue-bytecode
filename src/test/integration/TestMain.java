package test.integration;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import signatures.Signatures;
import util.OrderedPair;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
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
import analysis.pointer.registrar.RegistrationUtil;
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
public class TestMain {

    private static String classPath;

    /**
     * Run one of the selected tests
     * 
     * @param args
     *            <ol start="0">
     *            <li>The entry point (containing a main method) written as a full class name with packages separated by
     *            dots (e.g. java.lang.String)</li>
     *            <li>The verbosity level</li>
     *            <li>The test to run, see usage for the possible tests</li>
     * 
     *            </ol>
     * @throws IOException
     *             file writing issues
     * @throws ClassHierarchyException
     *             WALA set up issues
     */
    public static void main(String[] args) {
        try {
            if (args.length != 3 && args.length != 4) {
                throw new IllegalArgumentException(
                                                "The test harness takes three or four arguments, see usage for details.");
            }
            String entryPoint = args[0];
            if (entryPoint.equals("--usage") || entryPoint.equals("--help") || entryPoint.equals("-h")) {
                System.out.println(usage());
                return;
            }
            int outputLevel = Integer.parseInt(args[1]);
            String testName = args[2];

            int fileLevel = 0;
            if (args.length > 3) {
                fileLevel = Integer.parseInt(args[3]);
            }

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
            switch (testName) {
            case "pointsto":
                AnalysisUtil.init(classPath, entryPoint);
                results = generatePointsToGraph(outputLevel);
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
            case "pointsto2":
                AnalysisUtil.init(classPath, entryPoint);
                results = generatePointsToGraphOnline(outputLevel);
                g = results.fst();
                g.dumpPointsToGraphToFile(fileName + "_ptg", false);
                ((HafCallGraph) g.getCallGraph()).dumpCallGraphToFile(fileName + "_cg", false);

                System.err.println(g.getNodes().size() + " Nodes");
                num = 0;
                for (PointsToGraphNode n : g.getNodes()) {
                    num += g.getPointsToSet(n).size();
                }
                System.err.println(num + " Edges");
                System.err.println(g.getAllHContexts().size() + " HContexts");

                numNodes = 0;
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
            case "nonnull":
                AnalysisUtil.init(classPath, entryPoint);
                results = generatePointsToGraph(otherOutputLevel);
                g = results.fst();
                rvCache = results.snd();
                ReachabilityResults r = runReachability(otherOutputLevel, g, rvCache);
                NonNullResults nonNull = runNonNull(outputLevel, g, r, rvCache);
                nonNull.writeAllToFiles(r);
                break;
            case "precise-ex":
                AnalysisUtil.init(classPath, entryPoint);
                results = generatePointsToGraph(otherOutputLevel);
                g = results.fst();
                rvCache = results.snd();
                r = runReachability(otherOutputLevel, g, rvCache);
                nonNull = runNonNull(otherOutputLevel, g, r, rvCache);
                PreciseExceptionResults preciseEx = runPreciseExceptions(outputLevel, g, r, nonNull, rvCache);
                preciseEx.writeAllToFiles(r);
                break;
            case "reachability":
                AnalysisUtil.init(classPath, entryPoint);
                results = generatePointsToGraph(otherOutputLevel);
                g = results.fst();
                rvCache = results.snd();
                r = runReachability(outputLevel, g, rvCache);
                r.writeAllToFiles();
                break;
            case "cfg":
                AnalysisUtil.init(classPath, entryPoint);
                results = generatePointsToGraph(otherOutputLevel);
                g = results.fst();
                printAllCFG(g);
                break;
            case "pdg":
                AnalysisUtil.init(classPath, entryPoint);
                results = generatePointsToGraph(otherOutputLevel);
                g = results.fst();
                rvCache = results.snd();
                r = runReachability(otherOutputLevel, g, rvCache);
                nonNull = runNonNull(otherOutputLevel, g, r, rvCache);
                preciseEx = runPreciseExceptions(otherOutputLevel, g, r, nonNull, rvCache);
                ProgramDependenceGraph pdg = runPDG(outputLevel, g, r, preciseEx, rvCache);
                String fullName = "tests/pdg_" + fileName + ".dot";
                FileWriter file = new FileWriter(fullName);
                pdg.writeDot(file, true, 1);
                file.close();
                System.err.println("DOT written to " + fullName);
                if (fileLevel >= 1) {
                    pdg.intraProcDotToFile(1);
                }

                if (fileLevel >= 2) {
                    r.writeAllToFiles();
                    nonNull.writeAllToFiles(r);
                    preciseEx.writeAllToFiles(r);
                    printAllCFG(g);
                }
                break;
            default:
                System.err.println(args[2] + " is not a valid test name." + usage());
            }
        } catch (Exception e) {
            System.err.println(usage());
            System.err.println("Actual parameters: " + Arrays.toString(args) + "\n");
            throw new RuntimeException(e);
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
            if (!n.getMethod().isNative() && !printed.contains(n.getMethod())) {
                String fileName = "cfg_" + PrettyPrinter.methodString(n.getMethod());
                printSingleCFG(n.getIR(), fileName);
                printed.add(n.getMethod());
            } else if (n.getMethod().isNative()) {
                IR sigIR = Signatures.getSignatureIR(n.getMethod());
                if (sigIR != null) {
                    String fileName = "cfg_sig_" + PrettyPrinter.methodString(n.getMethod());
                    printSingleCFG(sigIR, fileName);
                    printed.add(n.getMethod());
                } else {
                    System.err.println("No CFG for native " + PrettyPrinter.cgNodeString(n));
                }
            }
        }
    }

    /**
     * Print the parameter mapping for the main method
     * 
     * @return String containing the documentation
     */
    private static String usage() {
        StringBuilder sb = new StringBuilder();
        sb.append("--useage, --help, -h all print this documentation\n\n");
        sb.append("Param 0: The entry point (containing a main method) written as a full class name with packages separated by dots (e.g. java.lang.String)\n");
        sb.append("Param 1: Level of output (higher means more console output)\n");
        sb.append("Param 2: Test name\n");
        sb.append("Param 3: (optional) File write level (higher means more files will be written)\n");
        sb.append("\tpointsto - runs the points-to analysis test, saves graph in tests folder with the name: \"entryClassName_ptg.dot\"\n");
        sb.append("\tmaincfg - prints the cfg for the main method to the tests folder with the name: \"entryClassName_main_cfg.dot\"\n");
        sb.append("\tnonnull - prints the results of an interprocedural non-null analysis to the tests folder prepended with \"nonnull_\" \n");
        sb.append("\tprecise-ex - prints the results of an interprocedural precise exception analysis to the tests folder prepended with \"precise_ex_\"\n");
        sb.append("\treachability - prints the results of an interprocedural reachability analysis to the tests folder prepended with \"reachability_\"\n");
        sb.append("\tcfg - prints the cfg for the all methods to the tests folder prepended with : \"cfg_\"\n");
        sb.append("\tpdg - prints the pdg in graphviz dot formattests folder prepended with : \"pdg_\"\n");
        return sb.toString();
    }

    /**
     * Generate the full points-to graph, print statistics, and save it to a file.
     * 
     * @param outputLevel
     *            print level
     * @return the resulting points-to graph, and cache of reference variables
     */
    private static OrderedPair<PointsToGraph, ReferenceVariableCache> generatePointsToGraph(int outputLevel) {

        // Gather all the points-to statements
        StatementRegistrationPass.outputLevel = outputLevel;
        StatementRegistrationPass pass = new StatementRegistrationPass();
        pass.run();
        StatementRegistrar registrar = pass.getRegistrar();
        ReferenceVariableCache rvCache = pass.getAllLocals();

        // HeapAbstractionFactory haf = new CallSiteSensitive(1);
        HeapAbstractionFactory haf = new TypeSensitive(2);
        PointsToAnalysis analysis = new PointsToAnalysisSingleThreaded(haf);
        PointsToAnalysis.outputLevel = outputLevel;
        PointsToGraph g = analysis.solve(registrar);

        System.out.println("Registered statements: " + pass.getRegistrar().getAllStatements().size());
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

        HeapAbstractionFactory haf1 = new TypeSensitive(2);
        HeapAbstractionFactory haf2 = new StaticCallSiteSensitive(2);
        HeapAbstractionFactory haf = new CrossProduct(haf1, haf2);

        // HeapAbstractionFactory haf = new TypeSensitive(2, 1);

        PointsToAnalysisSingleThreaded analysis = new PointsToAnalysisSingleThreaded(haf);
        PointsToAnalysis.outputLevel = outputLevel;
        RegistrationUtil online = new RegistrationUtil();
        RegistrationUtil.outputLevel = outputLevel;
        PointsToGraph g = analysis.solveAndRegister(online);

        System.out.println("Registered statements: " + online.getRegistrar().getAllStatements().size());
        if (outputLevel >= 2) {
            for (PointsToStatement s : online.getRegistrar().getAllStatements()) {
                System.err.println("\t" + s + " (" + s.getClass().getSimpleName() + ")");
            }
        }
        System.err.println(g.getNodes().size() + " PTG nodes.");
        System.err.println(g.getCallGraph().getNumberOfNodes() + " CG nodes.");

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
    private static NonNullResults runNonNull(int outputLevel, PointsToGraph g,
                                    ReachabilityResults r, ReferenceVariableCache rvCache) {
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
    private static PreciseExceptionResults runPreciseExceptions(int outputLevel,
                                    PointsToGraph g, ReachabilityResults r, NonNullResults nonNull,
                                    ReferenceVariableCache rvCache) {
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
     */
    private static ReachabilityResults runReachability(int outputLevel, PointsToGraph g, ReferenceVariableCache rvCache) {
        ReachabilityInterProceduralDataFlow analysis = new ReachabilityInterProceduralDataFlow(g, rvCache);
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
    private static ProgramDependenceGraph runPDG(int outputLevel, PointsToGraph g,
                                    ReachabilityResults r, PreciseExceptionResults preciseEx,
                                    ReferenceVariableCache rvCache) {
        PDGInterproceduralDataFlow analysis = new PDGInterproceduralDataFlow(g, preciseEx, r, rvCache);
        analysis.setOutputLevel(outputLevel);
        analysis.runAnalysis();
        return analysis.getAnalysisResults();
    }
}
