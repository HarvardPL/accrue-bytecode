package test.integration;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;

import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionInterproceduralDataFlow;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.interprocedural.nonnull.NonNullInterProceduralDataFlow;
import analysis.dataflow.interprocedural.nonnull.NonNullResults;
import analysis.dataflow.interprocedural.reachability.ReachabilityInterProceduralDataFlow;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.pointer.analyses.CallSiteSensitive;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysisSingleThreaded;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.StatementRegistrar;
import analysis.pointer.statements.StatementRegistrationPass;

import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.config.AnalysisScopeReader;

/**
 * Run one of the selected analyses or tests, see usage
 */
public class TestMain {

    /**
     * Run one of the selected tests
     * 
     * @param args
     *            <ol start="0">
     *            <li>The entry point (containing a main method) written as a
     *            full class name with packages separated by dots (e.g.
     *            java.lang.String)</li>
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
            if (args.length != 3) {
                throw new IllegalArgumentException("The test harness takes three arguments, see usage for details.");
            }
            String entryPoint = args[0];
            if (entryPoint.equals("--usage") || entryPoint.equals("--help") || entryPoint.equals("-h")) {
                System.out.println(usage());
                return;
            }
            int outputLevel = Integer.parseInt(args[1]);
            String testName = args[2];
            String fileName = entryPoint + "_main";
            WalaAnalysisUtil util;
            Entrypoint entry;
            IR ir;
            switch (testName) {
            case "pointsto":
                util = setUpWala(entryPoint);
                PointsToGraph g = generatePointsToGraph(util, outputLevel);
                g.dumpPointsToGraphToFile(fileName + "_ptg", false);

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
                util = setUpWala(entryPoint);
                entry = util.getOptions().getEntrypoints().iterator().next();
                ir = util.getCache().getIR(entry.getMethod());
                printSingleCFG(util, ir, fileName);
                break;
            case "nonnull":
                util = setUpWala(entryPoint);
                entry = util.getOptions().getEntrypoints().iterator().next();
                g = generatePointsToGraph(util, outputLevel);
                ReachabilityResults r = runReachability(util, outputLevel, g);
                NonNullResults nonNull = runNonNull(util, outputLevel, g, r);
                nonNull.writeAllToFiles(r);
                break;
            case "precise-ex":
                util = setUpWala(entryPoint);
                entry = util.getOptions().getEntrypoints().iterator().next();
                g = generatePointsToGraph(util, outputLevel);
                r = runReachability(util, outputLevel, g);
                nonNull = runNonNull(util, outputLevel, g, r);
                PreciseExceptionResults preciseEx = runPreciseExceptions(util, outputLevel, g, r, nonNull);
                preciseEx.writeAllToFiles(r);
                break;
            case "reachability":
                util = setUpWala(entryPoint);
                entry = util.getOptions().getEntrypoints().iterator().next();
                g = generatePointsToGraph(util, outputLevel);
                r = runReachability(util, outputLevel, g);
                r.writeAllToFiles();
                break;
            case "cfg":
                util = setUpWala(entryPoint);
                entry = util.getOptions().getEntrypoints().iterator().next();
                g = generatePointsToGraph(util, outputLevel);
                printAllCFG(util, g);
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
     * @param util
     *            utility objects from WALA
     * @param g
     *            points to graph
     * @throws IOException
     *             file issues
     */
    private static void printAllCFG(WalaAnalysisUtil util, PointsToGraph g) throws IOException {

        for (CGNode n : g.getCallGraph()) {
            String fileName = "cfg_" + PrettyPrinter.parseCGNode(n).replace(" ", "");
            try (Writer w = new FileWriter(fileName)) {
                printSingleCFG(util, n.getIR(), fileName);
            }
        }
    }

    /**
     * Set up the WALA framework
     * 
     * @param entryPoint
     *            class containing the main method (full name, dot separated)
     * @return Utility object used by other tests
     * @throws IOException
     *             reading exclusions file
     * @throws ClassHierarchyException
     *             setting up class hierarchy
     */
    private static WalaAnalysisUtil setUpWala(String entryPoint) throws IOException, ClassHierarchyException {
        /********************************
         * Start of WALA set up code
         ********************************/
        String classPath = "/Users/mu/Documents/workspace/WALA/walaAnalysis/classes";
        File exclusions = new File("/Users/mu/Documents/workspace/WALA/walaAnalysis/data/Exclusions.txt");

        AnalysisScope scope = AnalysisScopeReader.makePrimordialScope(exclusions);
        AnalysisScopeReader.addClassPathToScope(classPath, scope, ClassLoaderReference.Application);

        long start = System.currentTimeMillis();
        IClassHierarchy cha = ClassHierarchy.make(scope);
        System.out.println(cha.getNumberOfClasses() + " classes loaded. It took "
                                        + (System.currentTimeMillis() - start) + "ms");

        AnalysisCache cache = new AnalysisCache();

        // Add L to the name to indicate that this is a class name
        Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha, "L"
                                        + entryPoint.replace(".", "/"));
        AnalysisOptions options = new AnalysisOptions(scope, entrypoints);

        /********************************
         * End of WALA set up code
         ********************************/

        return new WalaAnalysisUtil(cha, cache, options);
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
        sb.append("\t\"pointsto\" runs the points-to analysis test, saves graph in tests folder with the name: \"entryClassName_ptg.dot\"\n");
        sb.append("\t\"maincfg\" prints the cfg for the main method to the tests folder with the name: \"entryClassName_main_cfg.dot\"\n");
        sb.append("\t\"nonnull\" prints the results of an interprocedural non-null analysis to the tests folder prepended with \"nonnull_\" \n");
        sb.append("\t\"precise-ex\" prints the results of an interprocedural precise exception analysis to the tests folder prepended with \"precise_ex_\"\n");
        sb.append("\t\"reachability\" prints the results of an interprocedural reachability analysis to the tests folder prepended with \"reachability_\"\n");
        sb.append("\t\"cfg\" prints the cfg for the all methods to the tests folder prepended with : \"cfg_\"\n");
        return sb.toString();
    }

    /**
     * Generate the full points-to graph, print statistics, and save it to a
     * file.
     * 
     * @param util
     *            utility objects from WALA
     * @param outputLevel
     *            print level
     * @return the resulting points-to graph
     */
    private static PointsToGraph generatePointsToGraph(WalaAnalysisUtil util, int outputLevel) {

        // Gather all the points-to statements
        StatementRegistrationPass pass = new StatementRegistrationPass(util);
        StatementRegistrationPass.VERBOSE = outputLevel;
        pass.run();
        System.out.println("Registered statements: " + pass.getRegistrar().getAllStatements().size());
        if (outputLevel >= 2) {
            for (PointsToStatement s : pass.getRegistrar().getAllStatements()) {
                System.err.println("\t" + s + " (" + s.getClass().getSimpleName() + ")");
            }
        }
        StatementRegistrar registrar = pass.getRegistrar();

        HeapAbstractionFactory context = new CallSiteSensitive();
        PointsToAnalysis analysis = new PointsToAnalysisSingleThreaded(context, util);
        PointsToGraph g = analysis.solve(registrar);
        return g;
    }

    /**
     * Print the control flow graph for the given method
     * 
     * @param util
     *            WALA utility classes
     * @param IR
     *            code for the method to be printed
     * @param fileName
     *            file to save the results (appended with "_cfg.dot")
     */
    private static void printSingleCFG(WalaAnalysisUtil util, IR ir, String fileName) {
        CFGWriter cfg = new CFGWriter(ir);
        String dir = "tests";
        String file = fileName + "_cfg";
        String fullFilename = dir + "/" + file + ".dot";
        try {
            Writer out = new BufferedWriter(new FileWriter(fullFilename));
            cfg.writeVerbose(out, "", "\\l");
            out.close();
            System.err.println("\nDOT written to: " + fullFilename);
        } catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    /**
     * Run the non-null analysis and return the results
     * 
     * @param util
     *            WALA utility classes
     * @param method
     *            method to print the results for
     * @param g
     *            points-to graph
     * @param r
     *            results of a reachability analysis
     * @return the results of the non-null analysis
     */
    private static NonNullResults runNonNull(WalaAnalysisUtil util, int outputLevel, PointsToGraph g,
                                    ReachabilityResults r) {
        NonNullInterProceduralDataFlow analysis = new NonNullInterProceduralDataFlow(g, r, util);
        analysis.setOutputLevel(outputLevel);
        analysis.runAnalysis();
        return analysis.getAnalysisResults();
    }

    /**
     * Run a precise exceptions analysis
     * 
     * @param util
     *            WALA analysis classes
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
    private static PreciseExceptionResults runPreciseExceptions(WalaAnalysisUtil util, int outputLevel,
                                    PointsToGraph g, ReachabilityResults r, NonNullResults nonNull) {
        PreciseExceptionInterproceduralDataFlow analysis = new PreciseExceptionInterproceduralDataFlow(g, nonNull, r,
                                        util);
        analysis.setOutputLevel(outputLevel);
        analysis.runAnalysis();
        return analysis.getAnalysisResults();
    }

    /**
     * Run the inter-procedural reachability analysis
     * 
     * @param util
     *            utility WALA classes
     * @param outputLevel
     *            logging level
     * @param g
     *            points-to graph
     */
    private static ReachabilityResults runReachability(WalaAnalysisUtil util, int outputLevel, PointsToGraph g) {
        ReachabilityInterProceduralDataFlow analysis = new ReachabilityInterProceduralDataFlow(g);
        analysis.setOutputLevel(outputLevel);
        analysis.runAnalysis();
        return analysis.getAnalysisResults();
    }
}
