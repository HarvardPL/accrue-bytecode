package android.statements;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;

public class AndroidStatementRegistrar extends StatementRegistrar {

    public AndroidStatementRegistrar(AndroidStatementFactory factory) {
        super(factory);
    }

    private final Map<IClass, Set<IMethod>> allCallbacks = new LinkedHashMap<>();

    /**
     * Get a map from class to callbacks invoked on that class that were collected during the pointer-analysis.
     *
     * @return callback methods collected
     */
    public Map<IClass, Set<IMethod>> getAllCallbacks() {
        return allCallbacks;
    }
}
