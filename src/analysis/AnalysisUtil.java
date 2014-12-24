package analysis;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import main.AccrueAnalysisMain;
import signatures.Signatures;
import util.print.CFGWriter;
import util.print.PrettyPrinter;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.impl.FakeRootMethod;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.config.AnalysisScopeReader;

/**
 * Utilities that are valid for an entire set of analyses, and can be accessed statically
 */
public class AnalysisUtil {
    /**
     * Cache containing and managing SSA IR we are analyzing
     */
    private static AnalysisCache cache;
    /**
     * Options for the analysis (e.g. entry points)
     */
    private static AnalysisOptions options;
    /**
     * Class hierarchy for the code being analyzed
     */
    private static IClassHierarchy cha;
    /**
     * WALA's fake root method (calls the entry points)
     */
    private static AbstractRootMethod fakeRoot;
    /**
     * Class for java.lang.String
     */
    private static IClass stringClass;
    /**
     * Class for java.lang.Object
     */
    private static IClass objectClass;
    /**
     * Class for java.lang.Throwable
     */
    private static IClass throwableClass;
    /**
     * Class for java.lang.Error
     */
    private static IClass errorClass;
    /**
     * Class for java.lang.Cloneable
     */
    private static IClass cloneableInterface;
    /**
     * Class for java.io.Serializable
     */
    private static IClass serializableInterface;
    /**
     * type of the field in java.lang.String
     */
    public static final TypeReference STRING_VALUE_TYPE = TypeReference.JavaLangObject;
    /**
     * Class for value field of java.lang.String
     */
    private static IClass stringValueClass;
    private static IClass privilegedActionClass;
    private static IClass privilegedExceptionActionClass;

    private static String outputDirectory;
    private static AnalysisScope scope;

    /**
     * File describing classes that should be ignored by all analyses, even the WALA class loader
     */
    private static final File EXCLUSIONS_FILE = new File("Exclusions.txt");
    /**
     * File containing the location of the java standard library and other standard jars
     */
    private static final String PRIMORDIAL_FILENAME = "primordial.txt";
    /**
     * Signatures
     */
    public static final Signatures signatures = new Signatures();

    /**
     * Methods should be accessed statically, make sure to call {@link AnalysisUtil#init(String, String)} before running
     * an analysis
     */
    private AnalysisUtil() {
        // Intentionally blank
    }

    // ANDROID
    //    public static void initDex(String androidLibLocation, String pathToApp) throws IOException, ClassHierarchyException {
    //        cache = new AnalysisCache(new DexIRFactory());
    //        long start = System.currentTimeMillis();
    //
    //        OrderedPair<IClassHierarchy, AnalysisScope> chaScope = AndroidInit.createAndroidCHAandAnalysisScope(pathToApp,
    //                                                                                                            EXCLUSIONS_FILE,
    //                                                                                                            androidLibLocation);
    //        cha = chaScope.fst();
    //        AnalysisScope scope = chaScope.snd();
    //        System.out.println(cha.getNumberOfClasses() + " classes loaded. It took "
    //                + (System.currentTimeMillis() - start) + "ms");
    //
    //        // Set up is a two phase process, first we perform a context-insensitive analysis to find all the reachable
    //        // callbacks in the application starting with the activities defined in the manifest. The second phase is to add
    //        // all these callbacks to the fake root method in a way that captures the Android application lifecycle.
    //
    //        // Phase 1: the entry points are the activities found in the manifest
    //        AndroidInit aInit = new AndroidInit(pathToApp);
    //        Set<Entrypoint> entries = aInit.getActivityEntryPoints();
    //        options = new AnalysisOptions(scope, entries);
    //        addEntriesToRootMethod();
    //        setUpCommonClasses();
    //        // We now have valid values for all AnalysisUtil fields, but the entrypoints (system callbacks) are only a
    //        // subset of the actual entrypoints. This subset gives a starting point for discovering the rest of the
    //        // entrypoints.
    //
    //        // Phase 2: Find all the call backs and set up the fake root
    //        Map<IClass, Set<IMethod>> callbacks = AndroidInit.findAllCallBacks();
    //        // Here we do not want to just add all the entrypoints to the fake root, we need to do something more clever
    //    }

    /**
     * Create a pass which will generate points-to statements
     *
     * @param classPath Java class path to load class filed from with entries separated by ":"
     * @param entryPoint entry point main method, e.g mypackage.mysubpackage.MyClass
     * @param outputDirectory directory to put outputfiles into
     *
     * @throws IOException Thrown when the analysis scope is invalid
     * @throws ClassHierarchyException Thrown by WALA during class hierarchy construction, if there are issues with the
     *             class path and for other reasons see {@link ClassHierarchy}
     */
    public static void init(String classPath, String entryPoint, String outputDirectory) throws IOException,
                                                                                        ClassHierarchyException {

        AnalysisUtil.outputDirectory = outputDirectory;
        AnalysisUtil.cache = new AnalysisCache();


        AnalysisUtil.scope = AnalysisScopeReader.readJavaScope(PRIMORDIAL_FILENAME,
                                                                EXCLUSIONS_FILE,
                                                                AnalysisUtil.class.getClassLoader());
        System.err.println("CLASSPATH=" + classPath);
        AnalysisScopeReader.addClassPathToScope(classPath, scope, ClassLoaderReference.Application);

        long start = System.currentTimeMillis();

        AnalysisUtil.cha = ClassHierarchy.make(scope);
        System.err.println(AnalysisUtil.cha.getNumberOfClasses() + " classes loaded. It took "
                + (System.currentTimeMillis() - start) + "ms");
        if (!AccrueAnalysisMain.testMode) {
            System.gc();
            System.err.println("USED " + (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1000000)
                    + "MB");
        }

        Iterable<Entrypoint> entrypoints;
        if (entryPoint == null) {
            entrypoints = Collections.emptySet();
        }
        else {
            // Add L to the name to indicate that this is a class name
            entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, AnalysisUtil.cha, "L"
                    + entryPoint.replace(".", "/"));
        }
        AnalysisUtil.options = new AnalysisOptions(scope, entrypoints);

        addEntriesToRootMethod();
        setUpCommonClasses();
        ensureSignatures();
    }

    private static void ensureSignatures() {
        IClass systemClass = cha.lookupClass(TypeReference.JavaLangSystem);
        Selector arrayCopy = Selector.make("arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V");
        IMethod m = cha.resolveMethod(systemClass, arrayCopy);
        if (getIR(m) == null) {
            System.err.println("WARNING: cannot resolve signatures. Ensure \"classes/signatures\" is on the analysis classpath set with \"-cp\".");
        } else {
            System.err.println("Signatures: ENABLED");
        }
    }

    private static void addEntriesToRootMethod() {
        // Set up the entry points
        fakeRoot = new FakeRootMethod(cha, options, cache);
        for (Entrypoint e : options.getEntrypoints()) {
            // Add in the fake root method that sets up the call to main
            SSAAbstractInvokeInstruction call = e.addCall(fakeRoot);

            if (call == null) {
                throw new RuntimeException("Missing entry point " + e);
            }
        }
        // Have to add return to maintain the invariant that two basic blocks
        // have more than one edge between them. Otherwise the exit basic block
        // could have an exception edge and normal edge from the same basic
        // block.
        fakeRoot.addReturn(-1, false);
        String fullFilename = outputDirectory + "/cfg_" + PrettyPrinter.methodString(fakeRoot);
        CFGWriter.writeToFile(fakeRoot, fullFilename);
    }

    private static void setUpCommonClasses() {
        stringClass = cha.lookupClass(TypeReference.JavaLangString);
        assert stringClass != null;
        stringValueClass = cha.lookupClass(STRING_VALUE_TYPE);
        assert stringValueClass != null;
        objectClass = cha.lookupClass(TypeReference.JavaLangObject);
        assert objectClass != null;
        throwableClass = cha.lookupClass(TypeReference.JavaLangThrowable);
        assert throwableClass != null;
        errorClass = cha.lookupClass(TypeReference.JavaLangError);
        assert errorClass != null;
        TypeName privTN = TypeName.string2TypeName("Ljava/security/PrivilegedAction");
        TypeReference privTR = TypeReference.findOrCreate(ClassLoaderReference.Primordial, privTN);
        privilegedActionClass = cha.lookupClass(privTR);
        assert privilegedActionClass != null;

        TypeName privETN = TypeName.string2TypeName("Ljava/security/PrivilegedExceptionAction");
        TypeReference privETR = TypeReference.findOrCreate(ClassLoaderReference.Primordial, privETN);
        privilegedExceptionActionClass = cha.lookupClass(privETR);
        assert privilegedExceptionActionClass != null;

        cloneableInterface = cha.lookupClass(TypeReference.JavaLangCloneable);
        assert cloneableInterface != null;
        serializableInterface = cha.lookupClass(TypeReference.JavaIoSerializable);
        assert serializableInterface != null;

    }

    /**
     * Cache of various analysis artifacts, contains the SSA IR
     *
     * @return WALA analysis cache
     */
    public static AnalysisCache getCache() {
        return cache;
    }

    /**
     * WALA analysis options, contains the entry-point
     *
     * @return WALA analysis options
     */
    public static AnalysisOptions getOptions() {
        return options;
    }

    /**
     * WALA's class hierarchy
     *
     * @return class hierarchy
     */
    public static IClassHierarchy getClassHierarchy() {
        return cha;
    }

    /**
     * The root method that calls the entry-points
     *
     * @return WALA fake root method (sets up and calls actual entry points)
     */
    public static AbstractRootMethod getFakeRoot() {
        return fakeRoot;
    }

    /**
     * Get the IR for the given method, returns null for native methods without signatures
     *
     * @param resolvedMethod method to get the IR for
     * @return the code for the given method, null for native methods
     */
    public static IR getIR(IMethod resolvedMethod) {
        IR sigIR = signatures.getSignatureIR(resolvedMethod);
        if (sigIR != null) {
            return sigIR;
        }

        if (resolvedMethod.isNative()) {
            // Native method with no signature
            return null;
        }

        return cache.getSSACache().findOrCreateIR(resolvedMethod, Everywhere.EVERYWHERE, options.getSSAOptions());
    }

    /**
     * Get the def-use results for the given method, returns null for native methods without signatures
     *
     * @param resolvedMethod method to get the def-use results for
     * @return the def-use for the given method, null for native methods
     */
    public static DefUse getDefUse(IMethod resolvedMethod) {
        IR sigIR = signatures.getSignatureIR(resolvedMethod);
        if (sigIR != null) {
            return new DefUse(sigIR);
        }

        if (resolvedMethod.isNative()) {
            // Native method with no signature
            return null;
        }

        return cache.getSSACache().findOrCreateDU(resolvedMethod, Everywhere.EVERYWHERE, options.getSSAOptions());
    }

    /**
     * Get the IR for the method represented by the call graph node, returns null for native methods without signatures
     *
     * @param n call graph node
     * @return the code for the given call graph node, null for native methods without signatures
     */
    public static IR getIR(CGNode n) {
        return getIR(n.getMethod());
    }

    /**
     * Get the canonical class for java.lang.String
     *
     * @return class
     */
    public static IClass getStringClass() {
        return stringClass;
    }

    /**
     * Get the canonical class for java.lang.Objecy
     *
     * @return class
     */
    public static IClass getObjectClass() {
        return objectClass;
    }

    /**
     * Get the canonical class for java.lang.Throwable
     *
     * @return class
     */
    public static IClass getThrowableClass() {
        return throwableClass;
    }

    /**
     * Get the canonical class for java.lang.Error
     *
     * @return class
     */
    public static IClass getErrorClass() {
        return errorClass;
    }

    public static IClass getCloneableInterface() {
        return cloneableInterface;
    }

    public static IClass getSerializableInterface() {
        return serializableInterface;
    }

    /**
     * Check whether the given method has a signature
     *
     * @param m method to check
     * @return true if the method has a signature implementation
     */
    public static boolean hasSignature(IMethod m) {
        return signatures.hasSignature(m);
    }

    public static IClass getStringValueClass() {
        return stringValueClass;
    }

    public static <W, T> ConcurrentHashMap<W, T> createConcurrentHashMap() {
        return new ConcurrentHashMap<>(16, 0.75f, Runtime.getRuntime().availableProcessors());
    }

    public static <T> Set<T> createConcurrentSet() {
        return Collections.newSetFromMap(AnalysisUtil.<T, Boolean> createConcurrentHashMap());
    }

    /**
     * Get the directory to put output files into
     *
     * @return folder name
     */
    public static String getOutputDirectory() {
        return outputDirectory;
    }

    public static AnalysisScope getScope() {
        return scope;
    }

}
