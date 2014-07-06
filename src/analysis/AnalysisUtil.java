package analysis;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

import org.scandroid.util.EntryPoints;

import signatures.Signatures;
import util.print.CFGWriter;

import com.ibm.wala.classLoader.DexFileModule;
import com.ibm.wala.classLoader.DexIRFactory;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.dex.util.config.DexAnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.ipa.callgraph.impl.DexFakeRootMethod;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.impl.FakeRootMethod;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
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
     * Class for java.lang.Throwable
     */
    private static IClass throwableClass;
    /**
     * Class for java.lang.Error
     */
    private static IClass errorClass;
    /**
     * type of the field in java.lang.String
     */
    public static final TypeReference STRING_VALUE_TYPE = TypeReference.JavaLangObject;
    /**
     * Class for value field of java.lang.String
     */
    private static IClass stringValueClass;
    public static IClass privilegedActionClass;
    public static IClass privilegedExceptionActionClass;

    /**
     * File describing classes that should be ignored by all analyses, even the WALA class loader
     */
    private static final File EXCLUSIONS_FILE = new File("data/Exclusions.txt");
    /**
     * File containing the location of the java standard library and other standard jars
     */
    private static final String PRIMORDIAL_FILENAME = "data/primordial.txt";
    /**
     * Class path to use if none is provided
     */
    private static final String DEFAULT_CLASSPATH = "classes";
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

    public static void initDex(String androidLibLocation, String pathToApp) throws IOException, ClassHierarchyException {
        cache = new AnalysisCache(new DexIRFactory());

        long start = System.currentTimeMillis();
        AnalysisScope scope = DexAnalysisScopeReader.makeAndroidBinaryAnalysisScope(pathToApp, EXCLUSIONS_FILE);
        scope.setLoaderImpl(ClassLoaderReference.Application, "com.ibm.wala.classLoader.WDexClassLoaderImpl");

        scope.setLoaderImpl(ClassLoaderReference.Primordial, "com.ibm.wala.classLoader.WDexClassLoaderImpl");

        ClassHierarchy concreteCHA;

        // From org.SCandroid.util.AndroidAnalysisContext
        URI androidLib = new File(androidLibLocation).toURI();
        if (androidLib.getPath().endsWith(".dex")) {
            Module dexMod = new DexFileModule(new File(androidLib));
            scope.addToScope(ClassLoaderReference.Primordial, dexMod);
            // try (JarFile appModelJar = new JarFile(new File("data/AppModel_dummy.jar"))) {
            // scope.addToScope(ClassLoaderReference.Application, appModelJar);
            concreteCHA = ClassHierarchy.make(scope);
            // }
        } else {
            try (JarFile androidJar = new JarFile(new File(androidLib))) {
                scope.addToScope(ClassLoaderReference.Primordial, androidJar);
                // try (JarFile appModelJar = new JarFile(new File("data/AppModel_dummy.jar"))) {
                // scope.addToScope(ClassLoaderReference.Application, appModelJar);
                concreteCHA = ClassHierarchy.make(scope);
                // }
            }
        }

        cha = concreteCHA;
        System.out.println(cha.getNumberOfClasses() + " classes loaded. It took "
                                        + (System.currentTimeMillis() - start) + "ms");

        // TODO not sure what this is for
        // AnalysisScope scope_appmodel =
        // DexAnalysisScopeReader.makeAndroidBinaryAnalysisScope(
        // androidLib,
        // exclusions);
        // scope_appmodel.setLoaderImpl(ClassLoaderReference.Application,
        // "com.ibm.wala.classLoader.WDexClassLoaderImpl");
        //
        // scope_appmodel.setLoaderImpl(ClassLoaderReference.Primordial,
        // "com.ibm.wala.classLoader.WDexClassLoaderImpl");
        // AndroidSpecs.addPossibleListeners(ClassHierarchy.make(scope_appmodel));

        // List<Entrypoint> entrypoints = EntryPoints.appModelEntry(concreteCHA);
        List<Entrypoint> entrypoints = EntryPoints.defaultEntryPoints(concreteCHA);
        options = new AnalysisOptions(scope, entrypoints);
        fakeRoot = new DexFakeRootMethod(cha, options, cache);

        setUpRootMethodAndClasses();
    }

    /**
     * Create a pass which will generate points-to statements
     * 
     * @param classPath
     *            Java class path to load class filed from with entries separated by ":"
     * @param entryPoint
     *            entry point main method, e.g mypackage.mysubpackage.MyClass
     * 
     * @throws IOException
     *             Thrown when the analysis scope is invalid
     * @throws ClassHierarchyException
     *             Thrown by WALA during class hierarchy construction, if there are issues with the class path and for
     *             other reasons see {@link ClassHierarchy}
     */
    public static void init(String classPath, String entryPoint) throws IOException, ClassHierarchyException {

        cache = new AnalysisCache();

        if (classPath == null) {
            classPath = DEFAULT_CLASSPATH;
        }

        AnalysisScope scope = AnalysisScopeReader.readJavaScope(PRIMORDIAL_FILENAME, EXCLUSIONS_FILE,
                                        AnalysisUtil.class.getClassLoader());
        AnalysisScopeReader.addClassPathToScope(classPath, scope, ClassLoaderReference.Application);

        long start = System.currentTimeMillis();

        cha = ClassHierarchy.make(scope);
        System.out.println(cha.getNumberOfClasses() + " classes loaded. It took "
                                        + (System.currentTimeMillis() - start) + "ms");

        // Add L to the name to indicate that this is a class name
        Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha, "L"
                                        + entryPoint.replace(".", "/"));
        options = new AnalysisOptions(scope, entrypoints);

        setUpRootMethodAndClasses();
    }

    private static void setUpRootMethodAndClasses() {
        // Set up the entry points
        fakeRoot = new FakeRootMethod(cha, options, cache);
        for (Iterator<? extends Entrypoint> it = options.getEntrypoints().iterator(); it.hasNext();) {
            Entrypoint e = it.next();
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
        CFGWriter.writeToFile(fakeRoot);

        stringClass = cha.lookupClass(TypeReference.JavaLangString);
        stringValueClass = cha.lookupClass(STRING_VALUE_TYPE);
        throwableClass = cha.lookupClass(TypeReference.JavaLangThrowable);
        errorClass = cha.lookupClass(TypeReference.JavaLangError);
        TypeName privTN = TypeName.string2TypeName("Ljava/security/PrivilegedAction");
        TypeReference privTR = TypeReference.findOrCreate(ClassLoaderReference.Primordial, privTN);
        privilegedActionClass = cha.lookupClass(privTR);

        TypeName privETN = TypeName.string2TypeName("Ljava/security/PrivilegedExceptionAction");
        TypeReference privETR = TypeReference.findOrCreate(ClassLoaderReference.Primordial, privETN);
        privilegedExceptionActionClass = cha.lookupClass(privETR);
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
     * @param resolvedMethod
     *            method to get the IR for
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
     * @param resolvedMethod
     *            method to get the def-use results for
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
     * @param n
     *            call graph node
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

    /**
     * Check whether the given method has a signature
     * 
     * @param m
     *            method to check
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

}
