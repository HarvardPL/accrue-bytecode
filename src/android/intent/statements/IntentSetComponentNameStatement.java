package android.intent.statements;

import android.intent.IntentRegistrar;
import android.intent.model.AbstractComponentName;
import android.intent.model.AbstractIntent;

public class IntentSetComponentNameStatement extends IntentStatement {

    public IntentSetComponentNameStatement(int receiver, AbstractComponentName cn) {
        // TODO Auto-generated constructor stub
    }

    public IntentSetComponentNameStatement(AbstractIntent newIntent, int use) {
        // TODO Auto-generated constructor stub
    }

    public IntentSetComponentNameStatement(int receiver, int use) {
        // TODO Auto-generated constructor stub
    }

    @Override
    public boolean process(IntentRegistrar registrar) {
        // TODO Auto-generated method stub
        return false;
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
