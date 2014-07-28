package android.intent.statements;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractIntent;

public class IntentSetActionStatement extends IntentStatement {

    public IntentSetActionStatement(int receiver, int use) {
        // TODO Auto-generated constructor stub
    }

    public IntentSetActionStatement(int receiver, AbstractString actionMain) {
        // TODO Auto-generated constructor stub
    }

    public IntentSetActionStatement(AbstractIntent newIntent, AbstractString actionMain) {
        // TODO Auto-generated constructor stub
    }

    public IntentSetActionStatement(AbstractIntent newIntent, int use) {
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
