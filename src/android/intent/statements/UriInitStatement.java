package android.intent.statements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractURI;
import android.net.Uri;

import com.ibm.wala.classLoader.IMethod;

/**
 * Initialization of a URI object
 */
public class UriInitStatement extends IntentStatement {
    private final List<Integer> arguments;
    private final int receiver;

    public UriInitStatement(int receiver, int arg, IMethod m) {
        super(m);
        this.receiver = receiver;
        this.arguments = Collections.singletonList(arg);
    }

    public UriInitStatement(int receiver, List<Integer> args, IMethod m) {
        super(m);
        this.receiver = receiver;
        this.arguments = args;
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractURI prev = registrar.getURI(receiver);
        if (prev == AbstractURI.ANY) {
            return registrar.setURI(receiver, AbstractURI.ANY);
        }

        List<AbstractString> args = new ArrayList<>(arguments.size());
        for (Integer argument : arguments) {
            AbstractString as = stringResults.get(argument);
            if (as.equals(AbstractString.ANY)) {
                return registrar.setURI(receiver, AbstractURI.ANY);
            }
            assert !as.getPossibleValues().isEmpty() : "Empty set of Strings for " + argument + " in "
                    + PrettyPrinter.methodString(getMethod());
            args.add(as);
        }

        Set<Uri> uris = new LinkedHashSet<>();
        if (arguments.size() == 1) {
            // URI.<init>(String spec)
            AbstractString arg = args.get(0);
            for (String s : arg.getPossibleValues()) {
                uris.add(Uri.parse(s));
            }
        }
        else if (arguments.size() == 3) {
            // URI.<init>(String scheme, String schemeSpecificPart, String fragment)
            for (String s0 : args.get(0).getPossibleValues()) {
                for (String s1 : args.get(1).getPossibleValues()) {
                    for (String s2 : args.get(2).getPossibleValues()) {
                        uris.add(Uri.fromParts(s0, s1, s2));
                    }
                }
            }
        }
        return registrar.setURI(receiver, AbstractURI.join(prev, AbstractURI.create(uris)));
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
        UriInitStatement other = (UriInitStatement) obj;
        if (arguments == null) {
            if (other.arguments != null) {
                return false;
            }
        }
        else if (!arguments.equals(other.arguments)) {
            return false;
        }
        if (receiver != other.receiver) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((arguments == null) ? 0 : arguments.hashCode());
        result = prime * result + receiver;
        return result;
    }

    @Override
    public String toString() {
        return receiver + " = URI.<init>(" + arguments.toString() + ")";
    }

}
