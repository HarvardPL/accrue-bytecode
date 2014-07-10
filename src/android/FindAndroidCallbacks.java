package android;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

public final class FindAndroidCallbacks {

    private final Set<IClass> callBackClasses;

    public FindAndroidCallbacks() {
        this.callBackClasses = loadCallBackFile("data/AndroidCallBacks.txt");
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
            String className = "L" + in.readLine().replace('.', '/');
            TypeReference type = TypeReference.findOrCreate(ClassLoaderReference.Application, className);
            IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(type);
            if (klass != null) {
                callbackClasses.add(klass);
            }
            else {
                System.err.println(className + " not found");
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
    public Set<IMethod> findOverriddenCallbacks(IClass klass) {
        Set<IMethod> callbacks = new LinkedHashSet<>();
        Collection<IClass> interfaces = klass.getAllImplementedInterfaces();
        for (IClass c : interfaces) {
            if (callBackClasses.contains(c)) {
                // This is one of our pre-defined callbacks for now assume that any methods defined in the interface are
                // call backs
                IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
                for (IMethod callback : c.getAllMethods()) {
                    // Find the method in this class that overrides the callback (could be in a super class, which is
                    // fine)
                    callbacks.add(cha.resolveMethod(klass, callback.getSelector()));
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
}
