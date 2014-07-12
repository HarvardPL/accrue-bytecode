package android;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.strings.Atom;

public final class FindAndroidCallbacks {

    private static final IClass ACTIVITY_CLASS;
    private static final Selector ON_CREATE;
    private static final Selector ON_START;
    private static final Selector ON_RESUME;
    private static final Selector ON_PAUSE;
    private static final Selector ON_STOP;
    private static final Selector ON_DESTROY;
    static {
        TypeReference activityType = TypeReference.findOrCreate(ClassLoaderReference.Primordial,
                                        "Landroid/app/Activity");
        ACTIVITY_CLASS = AnalysisUtil.getClassHierarchy().lookupClass(activityType);
        TypeName bundleName = TypeName.findOrCreate("Landroid/os/Bundle");
        Descriptor onCreateDesc = Descriptor.findOrCreate(new TypeName[] { bundleName }, TypeReference.VoidName);
        ON_CREATE = new Selector(Atom.findOrCreateAsciiAtom("onCreate"), onCreateDesc);

        Descriptor noArgVoidDesc = Descriptor.findOrCreate(new TypeName[] {}, TypeReference.VoidName);
        ON_START = new Selector(Atom.findOrCreateAsciiAtom("onStart"), noArgVoidDesc);
        ON_RESUME = new Selector(Atom.findOrCreateAsciiAtom("onResume"), noArgVoidDesc);
        ON_PAUSE = new Selector(Atom.findOrCreateAsciiAtom("onPause"), noArgVoidDesc);
        ON_STOP = new Selector(Atom.findOrCreateAsciiAtom("onStop"), noArgVoidDesc);
        ON_DESTROY = new Selector(Atom.findOrCreateAsciiAtom("onDestroy"), noArgVoidDesc);
    }
    
    private static Set<IClass> callbackClasses = null;

    public static Set<IClass> getCallBackClasses() {
        if (callbackClasses == null) {
            callbackClasses = loadCallBackFile("data/AndroidCallBacks.txt");
        }
        return callbackClasses;
    }

    /**
     * Load the list of common Android call backs from a file. The file should contain a list of classes containing
     * callbacks each on a different line.
     * 
     * @return Set of the classes containing callbacks
     */
    private static Set<IClass> loadCallBackFile(String filename) {
        Set<IClass> callbackClasses = new LinkedHashSet<>();
        try (BufferedReader in = new BufferedReader(new FileReader(filename))) {
            while (in.ready()) {
                String className = "L" + in.readLine().replace('.', '/');
                TypeReference type = TypeReference.findOrCreate(ClassLoaderReference.Application, className);
                IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(type);
                if (klass != null) {
                    System.err.println("CALLBACK class: " + PrettyPrinter.typeString(klass));
                    callbackClasses.add(klass);
                }
                else {
                    System.err.println("CALLBACK class: " + className + " not found");
                }
            }
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
        return callbackClasses;
    }

    /**
     * Get the set of callbacks explicitly registered in the given method
     * 
     * @return methods registered in the given method
     */
    private Set<IMethod> findRegisteredCallbacks(IClass klass) {
        // TODO Not sure if this is needed, could just handle these by calling findOverriddenCallBacks on allocation
        return null;
    }

    /**
     * Get the set of overridden callbacks
     * 
     * @return callback methods overridden
     */
    public static Set<IMethod> findOverriddenCallbacks(IClass klass) {
        assert klass != null;
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
        // XXX trigger the class loader so debug statements get printed in a nicer order
        klass.getAllMethods();
        Set<IMethod> callbacks = new LinkedHashSet<>();
        Collection<IClass> interfaces = klass.getAllImplementedInterfaces();
        System.err.println(PrettyPrinter.typeString(klass) + " implements " + interfaces);
        for (IClass c : interfaces) {
            if (getCallBackClasses().contains(c)) {
                System.err.println("\tcallback " + c);
                // This is one of our pre-defined callbacks for now assume that any methods defined in the interface are
                // call backs
                for (IMethod callback : c.getAllMethods()) {
                    if (!callback.getDeclaringClass().equals(cha.getRootClass())) {
                        // Find the method in this class that overrides the callback (could be in a super class, which
                        // is fine)
                        IMethod cbImplementation = cha.resolveMethod(klass, callback.getSelector());
                        System.err.println("\t\tADDING " + PrettyPrinter.methodString(cbImplementation) + " for "
                                                        + PrettyPrinter.methodString(callback));
                        callbacks.add(cbImplementation);
                    }
                }
            }
        }
        return callbacks;
    }

    /**
     * An application can define callbacks within a layout file, this method finds these callbacks
     * 
     * @param layoutFileName
     *            xml layout file
     * @return callbacks defined within a layout file
     */
    private Set<IMethod> findLayoutFileCallbacks(String layoutFileName) {
        return null;
    }

    public static Set<IMethod> activityLifecycleCallbacks(IClass activity) {
        assert AnalysisUtil.getClassHierarchy().isAssignableFrom(ACTIVITY_CLASS, activity);
        // TODO Auto-generated method stub
        return null;
    }
}
