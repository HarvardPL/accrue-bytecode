package test.integration;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;

import util.print.CFGWriter;
import analysis.WalaAnalysisUtil;
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
 * Run one of the selected tests, see usage
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
    public static void main(String[] args) throws IOException, ClassHierarchyException {
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

            WalaAnalysisUtil util;
            switch (testName) {
            case "pointsto":
                util = setUpWala(entryPoint);
                pointsToTest(util, outputLevel, entryPoint);
                break;
            case "maincfg":
                util = setUpWala(entryPoint);
                Entrypoint entry = util.getOptions().getEntrypoints().iterator().next();
                IR ir = util.getCache().getIR(entry.getMethod());
                cfgSingleTest(util, ir, entryPoint + "_main");
                break;
            default:
                System.err.println(args[2] + " is not a valid test name." + usage());
            }
        } catch (Exception e) {
            System.err.println(usage());
            System.err.println("Actual parameters: " + Arrays.toString(args));
            e.printStackTrace();
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

        sb.append("\t\"maincfg\" prints the cfg for the main method to the tests folder with the name: \"entryClassName_main_cfg.dot\"");
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
     * @param filename
     *            name of file to be printed to ("_ptg.dot" will be appended)
     */
    private static void pointsToTest(WalaAnalysisUtil util, int outputLevel, String filename) {

        // Gather all the points-to statements
        StatementRegistrationPass pass = new StatementRegistrationPass(util);
        // Don't print anything
        StatementRegistrationPass.VERBOSE = outputLevel;
        pass.run();
        System.out.println("Registered statements: " + pass.getRegistrar().getAllStatements().size());
        if (outputLevel >= 2) {
            for (PointsToStatement s : pass.getRegistrar().getAllStatements()) {
                System.out.println("\t" + s + " (" + s.getClass().getSimpleName() + ")");
            }
        }
        StatementRegistrar registrar = pass.getRegistrar();

        HeapAbstractionFactory context = new CallSiteSensitive();
        PointsToAnalysis analysis = new PointsToAnalysisSingleThreaded(context, util);
        PointsToGraph g = analysis.solve(registrar);
        g.dumpPointsToGraphToFile(filename + "_ptg", false);

        System.out.println(g.getNodes().size() + " Nodes");
        int num = 0;
        for (PointsToGraphNode n : g.getNodes()) {
            num += g.getPointsToSet(n).size();
        }
        System.out.println(num + " Edges");
        System.out.println(g.getAllHContexts().size() + " HContexts");

        int numNodes = 0;
        for (@SuppressWarnings("unused")
        CGNode n : g.getCallGraph()) {
            numNodes++;
        }
        System.out.println(numNodes + " CGNodes");
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
    private static void cfgSingleTest(WalaAnalysisUtil util, IR ir, String fileName) {
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
}
