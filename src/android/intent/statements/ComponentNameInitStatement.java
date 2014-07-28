package android.intent.statements;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractComponentName;

public class ComponentNameInitStatement extends IntentStatement {

    private final AbstractString packageName;
    private final AbstractString className;
    private final int valueNumber;

    /**
     *
     * @param valueNumber value number for the ComponentName object being initialized
     * @param className
     * @param packageName
     */
    public ComponentNameInitStatement(int valueNumber, AbstractString className, AbstractString packageName) {
        this.className = className;
        this.packageName = packageName;
        this.valueNumber = valueNumber;
    }

    @Override
    public boolean process(IntentRegistrar registrar) {
        AbstractComponentName previous = registrar.getComponentName(valueNumber);
        AbstractComponentName newComponent;
        if (previous != null) {
            newComponent = previous.join(packageName, className);
        } else {
            newComponent = new AbstractComponentName(packageName, className);
        }
        return registrar.setComponentName(newComponent);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((className == null) ? 0 : className.hashCode());
        result = prime * result + ((packageName == null) ? 0 : packageName.hashCode());
        result = prime * result + valueNumber;
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
        if (className == null) {
            if (other.className != null) {
                return false;
            }
        }
        else if (!className.equals(other.className)) {
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
        if (valueNumber != other.valueNumber) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "ComponentName.<init> { val:" + valueNumber + ", class:" + className + "pkg:" + packageName + " }";
    }

}
