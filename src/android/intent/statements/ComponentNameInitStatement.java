package android.intent.statements;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import analysis.string.AbstractString;
import android.content.ComponentName;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractComponentName;

import com.ibm.wala.classLoader.IMethod;

/**
 * Initialization of a android.content.ComponentName object
 */
public class ComponentNameInitStatement extends IntentStatement {

    private final int receiver;
    private final int packageValueNumber;
    private final int classValueNumber;
    private final String packageName;

    public ComponentNameInitStatement(int receiver, int packageValueNumber, int classValueNumber, IMethod m) {
        super(m);
        assert packageValueNumber >= 0;
        assert classValueNumber >= 0;
        assert receiver >= 0;
        this.receiver = receiver;
        this.packageValueNumber = packageValueNumber;
        this.classValueNumber = classValueNumber;
        this.packageName = null;
    }

    public ComponentNameInitStatement(int receiver, String packageName, int classValueNumber, IMethod m) {
        super(m);
        assert classValueNumber >= 0;
        assert receiver >= 0;
        assert packageName != null;
        this.receiver = receiver;
        this.packageName = packageName;
        this.packageValueNumber = -1;
        this.classValueNumber = classValueNumber;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractComponentName prev = registrar.getComponentName(receiver);
        if (prev == AbstractComponentName.ANY) {
            return registrar.setComponentName(receiver, AbstractComponentName.ANY);
        }

        AbstractString classNames = stringResults.get(classValueNumber);
        if (classNames == AbstractString.ANY) {
            return registrar.setComponentName(receiver, AbstractComponentName.ANY);
        }

        Set<ComponentName> newComponents = new LinkedHashSet<>();
        if (packageName != null) {
            // The exact package name is known
            for (String className : classNames.getPossibleValues()) {
                newComponents.add(new ComponentName(packageName, className));
            }
            AbstractComponentName newAbsCN = AbstractComponentName.join(AbstractComponentName.create(newComponents),
                                                                        prev);
            return registrar.setComponentName(classValueNumber, newAbsCN);
        }

        AbstractString packageNames = stringResults.get(packageValueNumber);
        if (packageNames == AbstractString.ANY) {
            return registrar.setComponentName(receiver, AbstractComponentName.ANY);
        }

        for (String pkgName : packageNames.getPossibleValues()) {
            for (String className : classNames.getPossibleValues()) {
                newComponents.add(new ComponentName(pkgName, className));
            }
        }
        AbstractComponentName newAbsCN = AbstractComponentName.join(AbstractComponentName.create(newComponents), prev);
        return registrar.setComponentName(classValueNumber, newAbsCN);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + classValueNumber;
        result = prime * result + ((packageName == null) ? 0 : packageName.hashCode());
        result = prime * result + packageValueNumber;
        result = prime * result + receiver;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        ComponentNameInitStatement other = (ComponentNameInitStatement) obj;
        if (classValueNumber != other.classValueNumber) {
            return false;
        }
        if (packageName == null) {
            if (other.packageName != null) {
                return false;
            }
        }
        else if (!packageName.equals(other.packageName)) {
            return false;
        }
        if (packageValueNumber != other.packageValueNumber) {
            return false;
        }
        if (receiver != other.receiver) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return receiver + " = ComponentName.<init>(" + (packageName == null ? packageValueNumber : packageName) + ", "
                + classValueNumber + ")";
    }

}
