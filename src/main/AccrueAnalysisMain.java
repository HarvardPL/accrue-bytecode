package main;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import org.json.JSONException;

import results.CollectResultsPass;
import results.CollectedResults;
import util.OrderedPair;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.dataflow.interprocedural.accessible.AccessibleLocationResults;
import analysis.dataflow.interprocedural.accessible.AccessibleLocationsInterproceduralDataFlow;
import analysis.dataflow.interprocedural.bool.BooleanConstantDataFlow;
import analysis.dataflow.interprocedural.bool.BooleanConstantResults;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionInterproceduralDataFlow;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.interprocedural.interval.IntervalInterProceduralDataFlow;
import analysis.dataflow.interprocedural.interval.IntervalResults;
import analysis.dataflow.interprocedural.nonnull.NonNullInterProceduralDataFlow;
import analysis.dataflow.interprocedural.nonnull.NonNullResults;
import analysis.dataflow.interprocedural.pdg.PDGInterproceduralDataFlow;
import analysis.dataflow.interprocedural.pdg.graph.ProgramDependenceGraph;
import analysis.dataflow.interprocedural.reachability.ReachabilityInterProceduralDataFlow;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.engine.PointsToAnalysisSingleThreaded;
import analysis.pointer.graph.HafCallGraph;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.StatementRegistrationPass;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.StatementFactory;
import analysis.string.AbstractString;
import analysis.string.StringAnalysisResults;
import analysis.string.StringVariableFactory;
import analysis.string.StringVariableFactory.StringVariable;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisOptions.ReflectionOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.ExplicitCallGraph;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.ReceiverTypeContextSelector;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.properties.WalaProperties;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.config.AnalysisScopeReader;

/**
 * Run one of the selected analyses or tests, see usage
 */
public class AccrueAnalysisMain {

    public static boolean testMode;
    public static int fileLevel;

    /**
     * Run one of the selected tests
     *
     * @param args options and parameters see useage (pass in "-h") for details
     * @throws IOException file writing issues
     * @throws ClassHierarchyException issues reading class files
     * @throws JSONException issues writing JSON file
     */
    public static void main(String[] args) throws IOException, ClassHierarchyException, JSONException {
        AccrueAnalysisOptions options = AccrueAnalysisOptions.getOptions(args);
        if (options.shouldPrintUseage()) {
            System.err.println(AccrueAnalysisOptions.getUseage());
            return;
        }

        if (options.useDebugVariableNames()) {
            // Print out the numerical variable names in addition to the names from the source code
            PrettyPrinter.setUseDebugVariableNames(true);
        }

        if (options.isParanoidPointerAnalysis()) {
            // Double check the results after running the multi-threaded pointer analysis
            PointsToAnalysisMultiThreaded.setParanoidMode(true);
        }

        String entryPoint = options.getEntryPoint();
        int outputLevel = options.getOutputLevel();
        String analysisName = options.getAnalysisName();
        AccrueAnalysisMain.fileLevel = options.getFileLevel();
        String classPath = options.getAnalysisClassPath();
        HeapAbstractionFactory haf = options.getHaf();
        boolean isOnline = options.registerOnline();
        boolean useSingleThreadedPointerAnalysis = options.useSingleThreadedPointerAnalysis();
        int numThreads = useSingleThreadedPointerAnalysis ? 1 : options.getNumThreads();
        AccrueAnalysisMain.testMode = options.isTestMode();
        boolean disableSignatures = options.shouldDisableSignatures();
        boolean useDefaultNativeSignatures = !options.shouldDisableDefaultNativeSignatures();
        boolean disableObjectClone = options.shouldDisableObjectClone();

        // Trade-offs for points-to analysis precision vs. size/time
        boolean singleGenEx = options.shouldUseSingleAllocForGenEx();
        boolean singleThrowable = options.shouldUseSingleAllocPerThrowableType();
        boolean singlePrimArray = options.shouldUseSingleAllocForPrimitiveArrays();
        boolean singleString = options.shouldUseSingleAllocForStrings();
        boolean singleWrappers = options.shouldUseSingleAllocForImmutableWrappers();
        boolean singleSwing = options.shouldUseSingleAllocForSwing();

        try {
            System.err.println("J2SE_dir is " + WalaProperties.loadProperties().getProperty(WalaProperties.J2SE_DIR));
        }
        catch (WalaException e) {
            e.printStackTrace();
        }

        int otherOutputLevel = 0;
        if (outputLevel >= 9) {
            otherOutputLevel = 9;
        }

        String outputDir = options.getOutputDir();

        String fileName = entryPoint;
        Entrypoint entry;
        IR ir;
        OrderedPair<PointsToGraph, ReferenceVariableCache> results;
        PointsToGraph g;
        ReferenceVariableCache rvCache;
        AccessibleLocationResults alr;
        AnalysisUtil.init(classPath, entryPoint, outputDir, numThreads, disableSignatures, disableObjectClone);
        switch (analysisName) {
        case "pointsto":
            runWalaPointerAnalysis(haf,
                                   singleGenEx,
                                   singleThrowable,
                                   singlePrimArray,
                                   singleString,
                                   singleWrappers,
                                   singleSwing,
                                   classPath,
                                   entryPoint,
                                   fileLevel,
                                   useDefaultNativeSignatures);
            break;
        case "pointsto2":
            results = generatePointsToGraph(outputLevel,
                                            haf,
                                            useSingleThreadedPointerAnalysis,
                                            isOnline,
                                            singleGenEx,
                                            singleThrowable,
                                            singlePrimArray,
                                            singleString,
                                            singleWrappers,
                                            singleSwing,
                                            useDefaultNativeSignatures);
            g = results.fst();
            //
            if (fileLevel > 0) {
                g.getCallGraph().dumpCallGraphToFile(outputDir + "/" + fileName + "_cg", false);
            }
            if (fileLevel > 1) {
                g.dumpPointsToGraphToFile(outputDir + "/" + fileName + "_ptg", false);
            }
            break;
        case "maincfg":
            entry = AnalysisUtil.getOptions().getEntrypoints().iterator().next();
            ir = AnalysisUtil.getIR(entry.getMethod());
            printSingleCFG(ir, outputDir + "/" + fileName + "_main");
            break;
        case "bool":
            runBooleanConstant(entryPoint,
                               outputLevel,
                               haf,
                               outputDir,
                               useSingleThreadedPointerAnalysis,
                               isOnline,
                               singleGenEx,
                               singleThrowable,
                               singlePrimArray,
                               singleString,
                               singleWrappers,
                               singleSwing,
                               useDefaultNativeSignatures);
            break;
        case "nonnull":
            results = generatePointsToGraph(outputLevel,
                                            haf,
                                            useSingleThreadedPointerAnalysis,
                                            isOnline,
                                            singleGenEx,
                                            singleThrowable,
                                            singlePrimArray,
                                            singleString,
                                            singleWrappers,
                                            singleSwing,
                                            useDefaultNativeSignatures);
            g = results.fst();
            rvCache = results.snd();
            ReachabilityResults r = runReachability(otherOutputLevel, g, rvCache, null);
            NonNullResults nonNull = runNonNull(outputLevel, g, r, rvCache);
            if (fileLevel > 0) {
                nonNull.writeAllToFiles(r, outputDir);
            }
        case "interval":
            results = generatePointsToGraph(outputLevel,
                                            haf,
                                            useSingleThreadedPointerAnalysis,
                                            isOnline,
                                            singleGenEx,
                                            singleThrowable,
                                            singlePrimArray,
                                            singleString,
                                            singleWrappers,
                                            singleSwing,
                                            useDefaultNativeSignatures);
            g = results.fst();
            rvCache = results.snd();
            r = runReachability(otherOutputLevel, g, rvCache, null);
            alr = runAccesibleLocations(otherOutputLevel, g, r, rvCache);
            IntervalResults interval = runInterval(outputLevel, g, r, rvCache, alr);
            if (fileLevel > 0) {
                interval.writeAllToFiles(r, outputDir);
            }
            break;
        case "collect":
            // Collect and print results for the flow sensitive points-to analysis
            results = generatePointsToGraph(outputLevel,
                                            haf,
                                            useSingleThreadedPointerAnalysis,
                                            isOnline,
                                            singleGenEx,
                                            singleThrowable,
                                            singlePrimArray,
                                            singleString,
                                            singleWrappers,
                                            singleSwing,
                                            useDefaultNativeSignatures);
            g = results.fst();
            rvCache = results.snd();
            r = runReachability(otherOutputLevel, g, rvCache, null);
            alr = runAccesibleLocations(otherOutputLevel, g, r, rvCache);
            interval = runInterval(outputLevel, g, r, rvCache, alr);
            nonNull = runNonNull(outputLevel, g, r, rvCache);

            CollectResultsPass cr = new CollectResultsPass(nonNull, interval, g);
            CollectedResults cResults = cr.run();
            System.err.println("NPE: "
                    + cResults.getNullPointerExceptionCount()
                    + "/"
                    + cResults.getPossibleNullPointerExceptionCount()
                    + " = "
                    + ((double) cResults.getNullPointerExceptionCount() / (double) cResults.getPossibleNullPointerExceptionCount()));
            System.out.println(cResults.getNullPointerExceptionCount());
            System.out.println(cResults.getPossibleNullPointerExceptionCount() + "\n");
            System.err.println("Casts Not Removed: " + cResults.getCastsNotRemovedCount() + "/"
                    + cResults.getTotalCastCount() + " = "
                    + ((double) cResults.getCastsNotRemovedCount() / cResults.getTotalCastCount()));
            System.out.println(cResults.getCastsNotRemovedCount());
            System.out.println(cResults.getTotalCastCount() + "\n");
            System.err.println("Arithmetic: "
                    + cResults.getArithmeticExceptionCount()
                    + "/"
                    + cResults.getPossibleArithmeticExceptionCount()
                    + " = "
                    + ((double) cResults.getArithmeticExceptionCount() / (double) cResults.getPossibleArithmeticExceptionCount()));
            System.out.println(cResults.getArithmeticExceptionCount());
            System.out.println(cResults.getPossibleArithmeticExceptionCount() + "\n");
            System.err.println("Zero intervals: " + cResults.getZeroIntervalCount() + "/"
                    + cResults.getPossibleZeroIntervalCount() + " = "
                    + ((double) cResults.getZeroIntervalCount() / (double) cResults.getPossibleZeroIntervalCount()));
            System.out.println(cResults.getZeroIntervalCount());
            System.out.println(cResults.getPossibleZeroIntervalCount() + "\n");
            System.err.println("Negative indices " + cResults.getNegIndex() + "/" + cResults.getPossibleNegIndex()
                    + " = " + ((double) cResults.getNegIndex() / (double) cResults.getPossibleNegIndex()));
            System.out.println(cResults.getNegIndex());
            System.out.println(cResults.getPossibleNegIndex() + "\n");
            System.out.println(g.getCallGraph().getNumberOfNodes());
            break;
        case "precise-ex":
            results = generatePointsToGraph(outputLevel,
                                            haf,
                                            useSingleThreadedPointerAnalysis,
                                            isOnline,
                                            singleGenEx,
                                            singleThrowable,
                                            singlePrimArray,
                                            singleString,
                                            singleWrappers,
                                            singleSwing,
                                            useDefaultNativeSignatures);
            g = results.fst();
            rvCache = results.snd();
            r = runReachability(otherOutputLevel, g, rvCache, null);
            nonNull = runNonNull(otherOutputLevel, g, r, rvCache);
            PreciseExceptionResults preciseEx = runPreciseExceptions(outputLevel, g, r, nonNull, rvCache);
            preciseEx.writeAllToFiles(r, outputDir);
            break;
        case "reachability":
            results = generatePointsToGraph(outputLevel,
                                            haf,
                                            useSingleThreadedPointerAnalysis,
                                            isOnline,
                                            singleGenEx,
                                            singleThrowable,
                                            singlePrimArray,
                                            singleString,
                                            singleWrappers,
                                            singleSwing,
                                            useDefaultNativeSignatures);
            g = results.fst();
            rvCache = results.snd();
            r = runReachability(outputLevel, g, rvCache, null);
            r.writeAllToFiles(outputDir);
            break;
        case "cfg":
            results = generatePointsToGraph(outputLevel,
                                            haf,
                                            useSingleThreadedPointerAnalysis,
                                            isOnline,
                                            singleGenEx,
                                            singleThrowable,
                                            singlePrimArray,
                                            singleString,
                                            singleWrappers,
                                            singleSwing,
                                            useDefaultNativeSignatures);
            g = results.fst();
            printAllCFG(g, outputDir);
            break;
        case "cfg-for-class":
            String name = "L" + options.getClassNameForCFG().replace(".", "/");
            System.err.println("Printing CFGs for " + name);
            TypeReference type = TypeReference.findOrCreate(ClassLoaderReference.Application, name);
            System.err.println("Found type " + type);
            IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(type);
            System.err.println("Found class " + klass);
            for (IMethod m : klass.getAllMethods()) {
                IR methodIR = AnalysisUtil.getIR(m);
                if (methodIR != null) {
                    String cfgFile = outputDir + "/cfg_" + PrettyPrinter.methodString(m) + ".dot";
                    CFGWriter.writeToFile(m, cfgFile);
                }
                else {
                    System.err.println("Did not print CFG for (native) method: " + PrettyPrinter.methodString(m));
                }
            }
            break;
        case "pdg":
            results = generatePointsToGraph(outputLevel,
                                            haf,
                                            useSingleThreadedPointerAnalysis,
                                            isOnline,
                                            singleGenEx,
                                            singleThrowable,
                                            singlePrimArray,
                                            singleString,
                                            singleWrappers,
                                            singleSwing,
                                            useDefaultNativeSignatures);
            g = results.fst();
            rvCache = results.snd();
            r = runReachability(otherOutputLevel, g, rvCache, null);
            nonNull = runNonNull(otherOutputLevel, g, r, rvCache);
            preciseEx = runPreciseExceptions(otherOutputLevel, g, r, nonNull, rvCache);
            ReachabilityResults r2 = runReachability(otherOutputLevel, g, rvCache, preciseEx);
            ProgramDependenceGraph pdg = runPDG(outputLevel, g, r2, preciseEx, nonNull, rvCache);
            pdg.printSimpleCounts();

            if (testMode) {
                // Don't print files in test mode
                return;
            }

            String fullName = outputDir + "/pdg_" + fileName + ".json";
            GZIPOutputStream gzip = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(fullName + ".gz")));
            try (Writer writer = new BufferedWriter(new OutputStreamWriter(gzip))) {
                pdg.writeJSON(writer);
                System.err.println("JSON written to " + fullName + ".gz");
            }

            if (fileLevel >= 1) {
                pdg.intraProcDotToFile(1, "", outputDir);
            }

            if (fileLevel >= 2) {
                printAllCFG(g, outputDir);
                r2.writeAllToFiles(outputDir);
                nonNull.writeAllToFiles(r, outputDir);
                preciseEx.writeAllToFiles(r, outputDir);
            }

            if (options.shouldWriteDotPDG()) {
                String dotName = outputDir + "/pdg_" + fileName + ".dot";
                try (FileWriter dotfile = new FileWriter(dotName)) {
                    pdg.writeDot(dotfile, true, 1);
                }
                System.err.println("DOT written to " + dotName);
                // Also write out the PDG for "main"
                pdg.intraProcDotToFile(1, "main", outputDir);

                // Also the non null results for main
                String nullfileName = outputDir + "/nonnull_main_" + fileName + ".dot";
                try (Writer w = new FileWriter(nullfileName)) {
                    nonNull.writeResultsForMethod(w, "main", r);
                    System.err.println("DOT written to " + nullfileName);
                }

                // Also the precise exceptions results for main
                String preciseExFileName = outputDir + "/preciseEx_main_" + fileName + ".dot";
                try (Writer w = new FileWriter(preciseExFileName)) {
                    preciseEx.writeResultsForMethod(w, "main", r);
                    System.err.println("DOT written to " + preciseExFileName);
                }
            }
            break;
        //        case "android-cfg":
        //            AnalysisUtil.initDex("android/android-4.4.2_r1.jar", "android/it.dancar.music.ligabue.apk");
        //            results = generatePointsToGraph(outputLevel, haf, isOnline);
        //            g = results.fst();
        //            printAllCFG(g);
        //            break;
        case "string-main":
            StringVariableFactory factory = new StringVariableFactory();
            StringAnalysisResults stringResults = new StringAnalysisResults(factory);
            IMethod main = AnalysisUtil.getOptions().getEntrypoints().iterator().next().getMethod();
            printSingleCFG(AnalysisUtil.getIR(main), outputDir + "/" + fileName + "_main");
            Map<StringVariable, AbstractString> res = stringResults.getResultsForMethod(main);
            for (StringVariable v : res.keySet()) {
                System.err.println(v + " = " + res.get(v));
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
     * @param g points to graph
     */
    private static void printAllCFG(PointsToGraph g, String directory) {
        Set<IMethod> printed = new LinkedHashSet<>();
        for (CGNode n : g.getCallGraph()) {
            IMethod m = n.getMethod();
            if (!printed.contains(m)) {
                printed.add(m);
                String prefix = "cfg_";
                if (AnalysisUtil.hasSignature(m)) {
                    prefix += "sig_";
                }
                String fileName = directory + "/" + prefix + PrettyPrinter.methodString(m);
                IR ir = AnalysisUtil.getIR(m);
                if (ir != null) {
                    printSingleCFG(ir, fileName);
                }
                else {
                    System.err.println("No CFG for " + PrettyPrinter.cgNodeString(n) + " it "
                            + (m.isNative() ? "was" : "wasn't") + " native");
                }
            }
        }
    }

    /**
     * Generate the full points-to graph, print statistics, and save it to a file.
     *
     * @param outputLevel print level
     * @param haf Definition of the abstraction for heap locations
     * @param singleThreaded whether to use a single-threaded pointer analysis
     * @param online if true then points-to statements are registered during pointer analysis, rather than before
     * @param useSingleAllocForGenEx If true then only one allocation will be made for each generated exception type.
     *            This will reduce the size of the points-to graph (and speed up the points-to analysis), but result in
     *            a loss of precision for such exceptions.
     * @param useSingleAllocForThrowable If true then only one allocation will be made for each type of throwable. This
     *            will reduce the size of the points-to graph (and speed up the points-to analysis), but result in a
     *            loss of precision for throwables.
     * @param useSingleAllocForPrimitiveArrays If true then only one allocation will be made for any kind of primitive
     *            array. Reduces precision, but improves performance.
     * @param useSingleAllocForStrings If true then only one allocation will be made for any string. This will reduce
     *            the size of the points-to graph (and speed up the points-to analysis), but result in a loss of
     *            precision for strings.
     * @param useSingleAllocForImmutableWrappers If true then only one allocation will be made for each type of
     *            immutable wrapper. This will reduce the size of the points-to graph (and speed up the points-to
     *            analysis), but result in a loss of precision for these classes. These are: java.lang.String, all
     *            primitive wrapper classes, and BigDecimal and BigInteger (if not overridden).
     * @param useSingleAllocForSwing If true then only one allocation will be made for each type of in the java swing
     *            API
     *
     * @param useDefaultNativeSignatures Whether to use a signature that allocates the return type when there is no
     *            other signature
     * @return the resulting points-to graph
     */
    private static OrderedPair<PointsToGraph, ReferenceVariableCache> generatePointsToGraph(int outputLevel,
                                                                                            HeapAbstractionFactory haf,
                                                                                            boolean singleThreaded,
                                                                                            boolean isOnline,
                                                                                            boolean useSingleAllocForGenEx,
                                                                                            boolean useSingleAllocForThrowable,
                                                                                            boolean useSingleAllocForPrimitiveArrays,
                                                                                            boolean useSingleAllocForStrings,
                                                                                            boolean useSingleAllocForImmutableWrappers,
                                                                                            boolean useSingleAllocForSwing,
                                                                                            boolean useDefaultNativeSignatures) {
        System.err.println(AnalysisUtil.entryPoint);
        PointsToAnalysis analysis;
        if (singleThreaded) {
            analysis = new PointsToAnalysisSingleThreaded(haf);
        }
        else {
            analysis = new PointsToAnalysisMultiThreaded(haf);
        }
        PointsToAnalysis.outputLevel = outputLevel;
        PointsToGraph g;
        StatementRegistrar registrar;
        StatementFactory factory = new StatementFactory();
        if (isOnline) {
            registrar = new StatementRegistrar(factory,
                                               useSingleAllocForGenEx,
                                               useSingleAllocForThrowable,
                                               useSingleAllocForPrimitiveArrays,
                                               useSingleAllocForStrings,
                                               useSingleAllocForImmutableWrappers,
                                               useSingleAllocForSwing,
                                               useDefaultNativeSignatures);
            g = analysis.solveAndRegister(registrar);
        }
        else {
            StatementRegistrationPass pass = new StatementRegistrationPass(factory,
                                                                           useSingleAllocForGenEx,
                                                                           useSingleAllocForThrowable,
                                                                           useSingleAllocForPrimitiveArrays,
                                                                           useSingleAllocForStrings,
                                                                           useSingleAllocForImmutableWrappers,
                                                                           useSingleAllocForSwing,
                                                                           useDefaultNativeSignatures);
            pass.run();
            registrar = pass.getRegistrar();
            PointsToAnalysis.outputLevel = outputLevel;
            g = analysis.solve(registrar);
        }

        System.err.println("Registered statements: " + registrar.size());
        if (outputLevel >= 2) {
            for (IMethod m : registrar.getRegisteredMethods()) {
                for (PointsToStatement s : registrar.getStatementsForMethod(m)) {
                    System.err.println("\t" + s + " (" + s.getClass().getSimpleName() + ")");
                }
            }
        }
        if (singleThreaded) {
            System.err.println(((PointsToAnalysisSingleThreaded) analysis).lines2 + " lines of code analyzed.");
            System.err.println(((PointsToAnalysisSingleThreaded) analysis).instructions + " instructions analyzed.");
        }

        ReferenceVariableCache rvCache = registrar.getRvCache();
        return new OrderedPair<>(g, rvCache);
    }

    private static void runWalaPointerAnalysis(final HeapAbstractionFactory haf, boolean useSingleAllocForGenEx,
                                               boolean useSingleAllocForThrowable,
                                               boolean useSingleAllocForPrimitiveArrays,
                                               boolean useSingleAllocForStrings,
                                               boolean useSingleAllocForImmutableWrappers,
                                               boolean useSingleAllocForSwing, String classPath,
                                               String entryPoint, int fileLevel, boolean useDefaultNativeSignatures)
                                                                                                                    throws IOException,
                                                                                                                    ClassHierarchyException {
        // Set up WALA's points-to analysis
        AnalysisCache cache = new AnalysisCache();
        AnalysisScope scope = AnalysisScopeReader.readJavaScope(AnalysisUtil.PRIMORDIAL_FILENAME,
                                                        AnalysisUtil.EXCLUSIONS_FILE,
                                                        AnalysisUtil.class.getClassLoader());
        System.err.println("CLASSPATH=" + classPath);
        AnalysisScopeReader.addClassPathToScope(classPath, scope, ClassLoaderReference.Application);
        ClassHierarchy cha = ClassHierarchy.make(scope);

        //        Collection<CGNode> entries = g.getCallGraph().getEntrypointNodes();
        Iterable<Entrypoint> entrypoints;
        if (entryPoint == null) {
            entrypoints = Collections.emptySet();
        }
        else {
            // Add L to the name to indicate that this is a class name
            entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha, "L"
                    + entryPoint.replace(".", "/"));
        }

        AnalysisOptions options = new AnalysisOptions(scope, entrypoints);

        options.setReflectionOptions(ReflectionOptions.NONE);
        // Clinits are computed by our analysis and passed in, WALA seems to analyze way too many
        //        options.setHandleStaticInit(false);
        SSAPropagationCallGraphBuilder builder = Util.makeVanillaZeroOneCFABuilder(options,
                                                                                   cache,
                                                                                   cha,
                                                                                   scope,
                                                                                   new ReceiverTypeContextSelector(),
                                                                                   null);

        try {
            System.err.println("STARTING WALA POINTER ANALYSIS");
            long start = System.currentTimeMillis();
            ExplicitCallGraph cg = (ExplicitCallGraph) builder.makeCallGraph(options, null);
            System.out.println((System.currentTimeMillis() - start) / 1000.0);
            long nodes = 0;
            long edges = 0;
            System.err.println("\n!!!!!!!!!!!!!!!! POINTS-TO-GRAPH-NODES !!!!!!!!!!!!!!!!");
            for (PointerKey key : builder.getPointerAnalysis().getPointerKeys()) {
                nodes++;
                edges += builder.getPointerAnalysis().getPointsToSet(key).size();
                //System.out.println(escape(key));
            }
            System.err.println("\tWALA CG nodes: " + cg.getNumberOfNodes());
            System.err.println("\tWALA PTG nodes: " + nodes);
            System.err.println("\tWALA PTG edges: " + edges);
            if (fileLevel > 0) {
                // Run our points-to analysis and compare the results to WALA's
                // This is only to find bugs not for performance comparison
                OrderedPair<PointsToGraph, ReferenceVariableCache> out = generatePointsToGraph(0,
                                                                                               haf,
                                                                                               false,
                                                                                               false,
                                                                                               useSingleAllocForGenEx,
                                                                                               useSingleAllocForThrowable,
                                                                                               useSingleAllocForPrimitiveArrays,
                                                                                               useSingleAllocForStrings,
                                                                                               useSingleAllocForImmutableWrappers,
                                                                                               useSingleAllocForSwing,
                                                                                               useDefaultNativeSignatures);
                PointsToGraph g = out.fst();
                HafCallGraph.dumpCallGraphToFile("walaCallGraph", false, cg);
                HafCallGraph.dumpCallGraphToFile("ourCallGraph", false, g.getCallGraph());
                compareCallGraphs(cg, g.getCallGraph(), true);
                System.err.println("##########################  REVERSE COMPARE  ##########################");
                compareCallGraphs(g.getCallGraph(), cg, false);
            }

        }
        catch (IllegalArgumentException | CancelException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Print the edges in call graph 1 where the first node is in call graph 2 but the edge is not
     *
     * @param cg1 call graph to compare to
     * @param cg2 call graph to compare
     * @param firstIsWala whether the first call graph is from WALA otherwise the second is
     */
    private static void compareCallGraphs(CallGraph cg1, CallGraph cg2, boolean firstIsWala) {
        for (CGNode n : cg2) {

            CGNode walaN = null;
            for (CGNode nn : cg1) {
                if (PrettyPrinter.cgNodeString(n).equals(PrettyPrinter.cgNodeString(nn))) {
                    walaN = nn;
                    break;
                }
            }
            if (walaN == null) {
                System.err.println("NODE: " + PrettyPrinter.cgNodeString(n)
                        + (firstIsWala ? " not in WALA" : " not in Ours"));
                continue;
            }

            Iterator<CGNode> walaSuccs = cg1.getSuccNodes(walaN);
            while (walaSuccs.hasNext()) {
                boolean found = false;
                CGNode walaSucc = walaSuccs.next();
                Iterator<CGNode> succs = cg2.getSuccNodes(n);
                while (succs.hasNext()) {
                    CGNode succ = succs.next();
                    if (PrettyPrinter.cgNodeString(walaSucc).equals(PrettyPrinter.cgNodeString(succ))) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    //                    if (walaSucc.getContext().toString().contains("Exception>")) {
                    //                        // We know we are more precise than WALA for exceptions
                    //                        continue;
                    //                    }
                    System.err.println("UNFOUND: " + PrettyPrinter.cgNodeString(n) + " -> "
                            + PrettyPrinter.cgNodeString(walaSucc));
                }
            }
        }
    }

    /**
     * Print the control flow graph for the given method
     *
     * @param IR code for the method to be printed
     * @param fileName file to save the results
     */
    private static void printSingleCFG(IR ir, String fileName) {
        CFGWriter cfg = new CFGWriter(ir);
        String fullFilename = fileName + ".dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            cfg.writeVerbose(out, "", "\\l");
            System.err.println("DOT written to: " + fullFilename);
        }
        catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    /**
     * Run the non-null analysis and return the results
     *
     * @param method method to print the results for
     * @param g points-to graph
     * @param r results of a reachability analysis
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
     * @param outputLevel level of logging
     * @param g points-to graph
     * @param r results of a reachability analysis
     * @param nonNull results of a non-null analysis
     * @return the results of the precise exceptions analysis
     */
    private static PreciseExceptionResults runPreciseExceptions(int outputLevel, PointsToGraph g,
                                                                ReachabilityResults r, NonNullResults nonNull,
                                                                ReferenceVariableCache rvCache) {
        PreciseExceptionInterproceduralDataFlow analysis = new PreciseExceptionInterproceduralDataFlow(g,
                                                                                                       nonNull,
                                                                                                       r,
                                                                                                       rvCache);
        analysis.setOutputLevel(outputLevel);
        analysis.runAnalysis();
        return analysis.getAnalysisResults();
    }

    /**
     * Run the inter-procedural reachability analysis
     *
     * @param outputLevel logging level
     * @param g points-to graph
     * @param rvCache cache of points-to analysis reference variables
     * @param preciseEx results of a precise exceptions analysis or null if none has been run yet
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
     * @param outputLevel logging level
     * @param g points-to graph
     * @param r results of a reachability analysis
     * @param preciseEx results of a precise exceptions analysis
     * @param nonNull results of a non-null analysis
     * @return the program dependence graph
     */
    private static ProgramDependenceGraph runPDG(int outputLevel, PointsToGraph g, ReachabilityResults r,
                                                 PreciseExceptionResults preciseEx, NonNullResults nonNull,
                                                 ReferenceVariableCache rvCache) {
        PDGInterproceduralDataFlow analysis = new PDGInterproceduralDataFlow(g, preciseEx, r, nonNull, rvCache);
        analysis.setOutputLevel(outputLevel);
        analysis.runAnalysis();
        return analysis.getAnalysisResults();
    }

    /**
     * Run the analysis to determine which locals are boolean constants and print the results
     *
     * @param entryPoint full name of class to print results for contained methods
     * @param outputLevel amount of debugging
     * @param haf heap abstraction factory defining analysis contexts
     * @param outputDir directory to print output to
     * @param singleThreaded should this use a single-threaded pointer analysis
     * @param isOnline should use an online points-to statement registration
     * @param useSingleAllocForGenEx If true then only one allocation will be made for each generated exception type.
     *            This will reduce the size of the points-to graph (and speed up the points-to analysis), but result in
     *            a loss of precision for such exceptions.
     * @param useSingleAllocForThrowable If true then only one allocation will be made for each type of throwable. This
     *            will reduce the size of the points-to graph (and speed up the points-to analysis), but result in a
     *            loss of precision for throwables.
     * @param useSingleAllocForPrimitiveArrays If true then only one allocation will be made for any kind of primitive
     *            array. Reduces precision, but improves performance.
     * @param useSingleAllocForStrings If true then only one allocation will be made for any string. This will reduce
     *            the size of the points-to graph (and speed up the points-to analysis), but result in a loss of
     *            precision for strings.
     * @param useSingleAllocForImmutableWrappers If true then only one allocation will be made for each type of
     *            immutable wrapper. This will reduce the size of the points-to graph (and speed up the points-to
     *            analysis), but result in a loss of precision for these classes. These are: java.lang.String, all
     *            primitive wrapper classes, and BigDecimal and BigInteger (if not overridden).
     * @param useDefaultNativeSignatures Whether to use a signature that allocates the return type when there is no
     *            other signature
     */
    private static void runBooleanConstant(String entryPoint, int outputLevel, HeapAbstractionFactory haf,
                                           String outputDir, boolean singleThreaded, boolean isOnline,
                                           boolean useSingleAllocForGenEx, boolean useSingleAllocForThrowable,
                                           boolean useSingleAllocForPrimitiveArrays, boolean useSingleAllocForStrings,
                                           boolean useSingleAllocForImmutableWrappers, boolean useSingleAllocForSwing,
                                           boolean useDefaultNativeSignatures) {
        OrderedPair<PointsToGraph, ReferenceVariableCache> results = generatePointsToGraph(outputLevel,
                                                                                           haf,
                                                                                           isOnline,
                                                                                           singleThreaded,
                                                                                           useSingleAllocForGenEx,
                                                                                           useSingleAllocForThrowable,
                                                                                           useSingleAllocForPrimitiveArrays,
                                                                                           useSingleAllocForStrings,
                                                                                           useSingleAllocForImmutableWrappers,
                                                                                           useSingleAllocForSwing,
                                                                                           useDefaultNativeSignatures);
        BooleanConstantDataFlow df = null;
        System.err.println("ENTRY: " + entryPoint);
        for (CGNode n : results.fst().getCallGraph()) {
            if (PrettyPrinter.methodString(n.getMethod()).contains(entryPoint)) {
                System.err.println("Analyzing: " + PrettyPrinter.cgNodeString(n));
                df = new BooleanConstantDataFlow(n, results.fst(), results.snd());
                BooleanConstantResults r = df.run();
                r.writeResultsToFile(outputDir);

                if (outputLevel >= 1) {
                    String cfgFile = outputDir + "/cfg_" + PrettyPrinter.methodString(n.getIR().getMethod()) + ".dot";
                    CFGWriter.writeToFile(n.getIR(), cfgFile);
                }
            }
        }

        if (df == null) {
            System.err.println("Could not find methods in: " + entryPoint);
            return;
        }
    }

    /**
     * Run the accessible locations analysis and return the results
     *
     * @param outputLevel amount of printing
     * @param g points-to graph
     * @param r results of a reachability analysis
     * @return the results of the accessible locations analysis
     */
    private static AccessibleLocationResults runAccesibleLocations(int outputLevel, PointsToGraph g,
                                                                   ReachabilityResults r, ReferenceVariableCache rvCache) {
        AccessibleLocationsInterproceduralDataFlow analysis = new AccessibleLocationsInterproceduralDataFlow(g,
                                                                                                             r,
                                                                                                             rvCache);
        analysis.setOutputLevel(outputLevel);
        analysis.runAnalysis();
        return analysis.getAnalysisResults();
    }

    /**
     * Run the interval analysis and return the results
     *
     * @param method method to print the results for
     * @param g points-to graph
     * @param r results of a reachability analysis
     * @return the results of the non-null analysis
     */
    private static IntervalResults runInterval(int outputLevel, PointsToGraph g, ReachabilityResults r,
                                               ReferenceVariableCache rvCache, AccessibleLocationResults alr) {
        // XXX change true to false to be flow insensitive
        IntervalInterProceduralDataFlow analysis = new IntervalInterProceduralDataFlow(g, r, rvCache, true, alr);
        analysis.setOutputLevel(outputLevel);
        analysis.runAnalysis();
        return analysis.getAnalysisResults();
    }

    @SuppressWarnings("unused")
    private static void runOurAnalysisInWALA() {
        //        com.ibm.wala.ipa.callgraph.multithread.analyses.HeapAbstractionFactory haf2 = new com.ibm.wala.ipa.callgraph.multithread.analyses.HeapAbstractionFactory() {
        //
        //            @Override
        //            public String toString() {
        //                return haf.toString();
        //            }
        //
        //            @Override
        //            public InstanceKey record(AllocSiteNode allocationSite, Context context) {
        //                analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode asn = new analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode(allocationSite.toString(),
        //                                                                                                                                                        allocationSite.getAllocatedClass(),
        //                                                                                                                                                        allocationSite.getAllocatingMethod(),
        //                                                                                                                                                        allocationSite.getProgramCounter(),
        //                                                                                                                                                        allocationSite.isStringLiteral(),
        //                                                                                                                                                        allocationSite.getLineNumber()) {
        //                    @Override
        //                    public boolean equals(Object obj) {
        //                        if (!(obj instanceof analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode)) {
        //                            return false;
        //                        }
        //                        analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode other = (analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode) obj;
        //                        return this.getAllocatedClass().equals(other.getAllocatedClass())
        //                                && this.getAllocatingMethod().equals(other.getAllocatingMethod())
        //                                && this.getProgramCounter() == other.getProgramCounter()
        //                                && this.isStringLiteral() == other.isStringLiteral()
        //                                && this.getLineNumber() == other.getLineNumber();
        //                    }
        //
        //                    @Override
        //                    public int hashCode() {
        //                        return this.getAllocatedClass().hashCode() + this.getAllocatingMethod().hashCode()
        //                                + this.getProgramCounter() + (this.isStringLiteral() ? 1 : 0) + this.getLineNumber();
        //                    }
        //
        //                };
        //
        //                return haf.record(asn, context);
        //            }
        //
        //            @Override
        //            public Context merge(CallSiteLabel callSite, InstanceKey receiver, Context callerContext) {
        //                analysis.pointer.statements.CallSiteLabel csl = new analysis.pointer.statements.CallSiteLabel(callSite.getCaller(),
        //                                                                                                              callSite.getReference());
        //                return haf.merge(csl, receiver, callerContext);
        //            }
        //
        //            @Override
        //            public Context initialContext() {
        //                return haf.initialContext();
        //            }
        //        };
        //        MultiThreadedCallGraphBuilder builder = new MultiThreadedCallGraphBuilder(AnalysisUtil.getOptions(),
        //                                                                                  AnalysisUtil.getCache(),
        //                                                                                  AnalysisUtil.getClassHierarchy(),
        //                                                                                  AnalysisUtil.getScope(),
        //                                                                                  haf2,
        //                                                                                  useSingleAllocForGenEx,
        //                                                                                  useSingleAllocForThrowable,
        //                                                                                  useSingleAllocForPrimitiveArrays,
        //                                                                                  useSingleAllocForStrings,
        //                                                                                  useSingleAllocForImmutableWrappers);
    }
}
