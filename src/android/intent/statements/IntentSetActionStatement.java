package android.intent.statements;

import java.util.Map;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractIntent;

import com.ibm.wala.classLoader.IMethod;

public class IntentSetActionStatement extends IntentStatement {

    private final int intentValueNumber;
    private final String preciseAction;
    private final int actionValueNumber;

    public IntentSetActionStatement(int receiver, int actionValueNumber, IMethod m) {
        this(receiver, actionValueNumber, null, m);
    }

    public IntentSetActionStatement(int receiver, String preciseAction, IMethod m) {
        this(receiver, -1, preciseAction, m);
    }

    private IntentSetActionStatement(int intentValueNumber, int actionValueNumber, String preciseAction, IMethod m) {
        super(m);
        this.intentValueNumber = intentValueNumber;
        this.preciseAction = preciseAction;
        this.actionValueNumber = actionValueNumber;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractIntent prev = registrar.getIntent(intentValueNumber);
        AbstractString action;
        if (preciseAction == null) {
            assert actionValueNumber >= 0 : "Invalid action value number: " + actionValueNumber;
            action = stringResults.get(actionValueNumber);
        }
        else {
            action = AbstractString.create(preciseAction);
        }
        return registrar.setIntent(intentValueNumber, prev.joinAction(action));
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
        IntentSetActionStatement other = (IntentSetActionStatement) obj;
        if (actionValueNumber != other.actionValueNumber) {
            return false;
        }
        if (intentValueNumber != other.intentValueNumber) {
            return false;
        }
        if (preciseAction == null) {
            if (other.preciseAction != null) {
                return false;
            }
        }
        else if (!preciseAction.equals(other.preciseAction)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + actionValueNumber;
        result = prime * result + intentValueNumber;
        result = prime * result + ((preciseAction == null) ? 0 : preciseAction.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "IntentSetActionStatement [intentValueNumber=" + intentValueNumber + ", preciseAction=" + preciseAction
                + ", actionValueNumber=" + actionValueNumber + "]";
    }

}
