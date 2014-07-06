package android;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import analysis.AnalysisUtil;
import android.manifest.ManifestFile;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

public class AndroidInit {

    private ManifestFile manifest;

    public AbstractRootMethod getFakeRoot() {
        return null;
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
     * Get a map from class to any static layout resource IDs used to set content in that class
     * 
     * @return map from class to layout resource ID number
     */
    private Map<IClass, Set<Integer>> classToLayoutResourceID() {
        return null;
    }

    /**
     * Get the set of callbacks explicitly registered in the code
     * 
     * @return methods registered in the analyzed application
     */
    private Set<IMethod> registeredCallbacks() {
        return null;
    }

    private void processManifest(String apkFileName) {
        this.manifest = new ManifestFile(apkFileName);
    }
}
