package android.intent.statements;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import analysis.string.AbstractString;
import android.content.ComponentName;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractComponentName;

import com.ibm.wala.classLoader.IMethod;

public class ComponentNameUnflattenStatement extends IntentStatement {

    private int def;
    private int stringValueNumber;

    public ComponentNameUnflattenStatement(int def, int stringValueNumber, IMethod m) {
        super(m);
        this.def = def;
        this.stringValueNumber = stringValueNumber;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractString str = stringResults.get(stringValueNumber);
        if (str == AbstractString.ANY) {
            return registrar.setComponentName(def, AbstractComponentName.ANY);
        }
        AbstractComponentName prev = registrar.getComponentName(def);
        Set<ComponentName> components = new LinkedHashSet<>();
        for (String s : str.getPossibleValues()) {
            components.add(ComponentName.unflattenFromString(s));
        }
        return registrar.setComponentName(def,
                                          AbstractComponentName.join(prev, AbstractComponentName.create(components)));
    }

    @Override
    public boolean equals(Object obj) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public int hashCode() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public String toString() {
        // TODO Auto-generated method stub
        return null;
    }

}
