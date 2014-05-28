package analysis;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import signatures.Signatures;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.impl.FakeRootMethod;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
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
    private static FakeRootMethod fakeRoot;
    /**
     * WALA representation of java.lang.String
     */
    private static IClass stringClass;
    /**
     * WALA representation of the class for the value field of a string
     */
    private static IClass stringValueClass;
    /**
     * WALA representation of the class for java.lang.Throwable
     */
    private static IClass throwableClass;
    /**
     * WALA representation of the class for java.lang.Error
     */
    private static IClass errorClass;
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
     * type of the field in java.lang.String
     */
    private static final TypeReference STRING_VALUE_TYPE = TypeReference.JavaLangObject;

    /**
     * Methods should be accessed statically, make sure to call {@link AnalysisUtil#init(String, String)} before
     * running an analysis
     */
    private AnalysisUtil() {
        // Intentionally blank
    }

    /**
     * Create a pass which will generate points-to statements
     * 
     * @param entryPoint
     *            entry point main method, e.g mypackage.mysubpackage.MyClass
     * @param classPath
     *            Java class path to load class filed from with entries separated by ":"
     * @throws IOException
     *             Thrown when the analysis scope is invalid
     * @throws ClassHierarchyException
     *             Thrown by WALA during class hierarchy construction, if there are issues with the class path and for
     *             other reasons see {@link ClassHierarchy}
     */
    public static void init(String entryPoint, String classPath) throws IOException, ClassHierarchyException {

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
        stringClass = cha.lookupClass(TypeReference.JavaLangString);
        stringValueClass = cha.lookupClass(STRING_VALUE_TYPE);
        throwableClass = cha.lookupClass(TypeReference.JavaLangThrowable);
        errorClass = cha.lookupClass(TypeReference.JavaLangError);
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
    public static FakeRootMethod getFakeRoot() {
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
        IR sigIR = Signatures.getSignatureIR(resolvedMethod);
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
     * Get the canonical class for java.lang.String
     * 
     * @return class
     */
    public static IClass getStringClass() {
        return stringClass;
    }

    /**
     * Get the canonical class for the value field of java.lang.String
     * 
     * @return class
     */
    public static IClass getStringValueClass() {
        return stringValueClass;
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
}
