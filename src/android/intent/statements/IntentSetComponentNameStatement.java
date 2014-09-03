package android.intent.statements;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import analysis.string.AbstractString;
import android.content.ComponentName;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractComponentName;
import android.intent.model.AbstractIntent;

import com.ibm.wala.classLoader.IMethod;

public class IntentSetComponentNameStatement extends IntentStatement {

    private final String precisePackageName;
    private final int classValueNumber;
    private final int packageValueNumber;

    private final int intentValueNumber;
    private final int componentNameValueNumber;

    private IntentSetComponentNameStatement(String precisePackageName, int packageValueNumber, int classValueNumber,
                                            int intentValueNumber, int componentNameValueNumber, IMethod m) {
        super(m);
        this.precisePackageName = precisePackageName;
        this.packageValueNumber = packageValueNumber;
        this.classValueNumber = classValueNumber;
        this.intentValueNumber = intentValueNumber;
        this.componentNameValueNumber = componentNameValueNumber;
    }

    public IntentSetComponentNameStatement(int receiver, int componentNameValueNumber, IMethod m) {
        this(null, -1, -1, receiver, componentNameValueNumber, m);
    }

    public IntentSetComponentNameStatement(int receiver, String packageName, int classValueNumber, IMethod m) {
        this(packageName, -1, classValueNumber, receiver, -1, m);
    }

    public IntentSetComponentNameStatement(int receiver, int packageValueNumber, int classValueNumber, IMethod m) {
        this(null, packageValueNumber, classValueNumber, receiver, -1, m);
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractIntent prev = registrar.getIntent(intentValueNumber);
        AbstractComponentName cn;
        if (precisePackageName == null && componentNameValueNumber >= 0) {
            cn = registrar.getComponentName(componentNameValueNumber);
        }
        else if (precisePackageName != null) {
            assert classValueNumber >= 0 : "Invalid class value number: " + classValueNumber;
            AbstractString classes = stringResults.get(classValueNumber);
            if (classes == AbstractString.ANY) {
                // TODO could be more precise here, still know the package name
                cn = AbstractComponentName.ANY;
            }
            else {
                Set<ComponentName> components = new LinkedHashSet<>();
                for (String className : classes.getPossibleValues()) {
                    components.add(new ComponentName(precisePackageName, className));
                }
                cn = AbstractComponentName.create(components);
            }
        }
        else {
            assert packageValueNumber >= 0 : "Invalid packageValueNumber value number: " + classValueNumber;
            assert classValueNumber >= 0 : "Invalid packageValueNumber value number: " + classValueNumber;
            AbstractString classes = stringResults.get(classValueNumber);
            AbstractString packages = stringResults.get(packageValueNumber);
            if (classes == AbstractString.ANY || packages == AbstractString.ANY) {
                // TODO could be more precise here, still know the package name
                cn = AbstractComponentName.ANY;
            }
            else {
                Set<ComponentName> components = new LinkedHashSet<>();
                for (String packageString : packages.getPossibleValues()) {
                    for (String className : classes.getPossibleValues()) {
                        components.add(new ComponentName(packageString, className));
                    }
                }
                cn = AbstractComponentName.create(components);
            }
        }
        return registrar.setIntent(intentValueNumber, prev.joinComponentName(cn));
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
        IntentSetComponentNameStatement other = (IntentSetComponentNameStatement) obj;
        if (classValueNumber != other.classValueNumber) {
            return false;
        }
        if (componentNameValueNumber != other.componentNameValueNumber) {
            return false;
        }
        if (intentValueNumber != other.intentValueNumber) {
            return false;
        }
        if (precisePackageName == null) {
            if (other.precisePackageName != null) {
                return false;
            }
        }
        else if (!precisePackageName.equals(other.precisePackageName)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + classValueNumber;
        result = prime * result + componentNameValueNumber;
        result = prime * result + intentValueNumber;
        result = prime * result + ((precisePackageName == null) ? 0 : precisePackageName.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "IntentSetComponentNameStatement [precisePackageName=" + precisePackageName + ", classValueNumber="
                + classValueNumber + ", intentValueNumber=" + intentValueNumber + ", componentNameValueNumber="
                + componentNameValueNumber + "]";
    }

}
