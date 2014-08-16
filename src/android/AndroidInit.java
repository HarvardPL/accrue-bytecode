package android;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.CallSiteSensitive;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysisSingleThreaded;
import android.manifest.ManifestFile;
import android.statements.AndroidStatementFactory;
import android.statements.AndroidStatementRegistrar;

import com.ibm.wala.classLoader.DexFileModule;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.dex.util.config.DexAnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

public class AndroidInit {

    private final ManifestFile manifest;
    /**
     * Class loader to use when making new types to find classes for
     */
    private static final ClassLoaderReference CLASS_LOADER = ClassLoaderReference.Application;

    public AndroidInit(String apkFileName) {
        this.manifest = new ManifestFile(apkFileName);
    }

    public static Map<IClass, Set<IMethod>> findAllCallBacks() {
        AndroidStatementRegistrar registrar = new AndroidStatementRegistrar(new AndroidStatementFactory());
        // Context insensitive analysis
        HeapAbstractionFactory haf = new CallSiteSensitive(0);
        PointsToAnalysisSingleThreaded analysis = new PointsToAnalysisSingleThreaded(haf);
        analysis.solveAndRegister(registrar);

        return registrar.getAllCallbacks();
    }

    public Set<Entrypoint> getActivityEntryPoints() {
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
        Set<Entrypoint> activityEntries = new LinkedHashSet<>();
        Set<IMethod> allCallbacks = new LinkedHashSet<>();
        // Start with all activities defined in the manifest
        // TODO could start with just the root and find the other ones via calls to startActivity
        for (String className : manifest.getAllActivityNames()) {
            if (className.startsWith(".")) {
                // need to prepend the package name
                className = manifest.getPackageName() + className;
            }
            className = "L" + className.replace(".", "/");
            System.err.println(className);
            TypeReference activityType = TypeReference.findOrCreate(CLASS_LOADER, className);
            IClass activity = cha.lookupClass(activityType);
            assert activity != null : "No class found for " + activityType + " with class name " + className;
            Set<IMethod> activityCallbacks = FindAndroidCallbacks.activityLifecycleCallbacks(activity);
            allCallbacks.addAll(activityCallbacks);
            Set<IMethod> overrideCallbacks = FindAndroidCallbacks.findOverriddenCallbacks(activity);
            allCallbacks.addAll(overrideCallbacks);
        }
        for (IMethod cb : allCallbacks) {
            DefaultEntrypoint ep = new DefaultEntrypoint(cb, cha);
            System.err.println("FOUND entry point " + PrettyPrinter.methodString(cb));
            activityEntries.add(ep);
        }

        return activityEntries;
    }

    public static OrderedPair<IClassHierarchy, AnalysisScope> createAndroidCHAandAnalysisScope(String apkFileName,
                                    File exclusions, String androidLibLocation) throws IOException,
                                    ClassHierarchyException {
        AnalysisScope scope = DexAnalysisScopeReader.makeAndroidBinaryAnalysisScope(apkFileName, exclusions);
        scope.setLoaderImpl(ClassLoaderReference.Application, "com.ibm.wala.classLoader.WDexClassLoaderImpl");
        scope.setLoaderImpl(ClassLoaderReference.Primordial, "com.ibm.wala.classLoader.WDexClassLoaderImpl");

        // From org.SCandroid.util.AndroidAnalysisContext
        URI androidLib = new File(androidLibLocation).toURI();
        IClassHierarchy cha;
        if (androidLib.getPath().endsWith(".dex")) {
            Module dexMod = new DexFileModule(new File(androidLib));
            scope.addToScope(ClassLoaderReference.Primordial, dexMod);
            cha = ClassHierarchy.make(scope);
        }
        else {
            try (JarFile androidJar = new JarFile(new File(androidLib))) {
                scope.addToScope(ClassLoaderReference.Primordial, androidJar);
                cha = ClassHierarchy.make(scope);
            }
        }
        return new OrderedPair<>(cha, scope);
    }
}
