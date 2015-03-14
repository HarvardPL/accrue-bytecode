package analysis.pointer.graph;

import java.util.Collections;
import java.util.Map;

import analysis.AnalysisUtil;
import analysis.pointer.analyses.AString;

public class StringConstraints {

    private final Map<StringVariableReplica, AString> map;
    private static final int MAX_STRING_SET_SIZE = 5;
    private static final AString INITIAL_STRING = AString.makeStringSet(MAX_STRING_SET_SIZE, Collections.singleton(""));

    /* Factory Methods */

    public static StringConstraints make() {
        return new StringConstraints();
    }

    /* Constructors */

    private StringConstraints() {
        this.map = AnalysisUtil.createConcurrentHashMap();
    }

    /* Logic */

    public AString getAStringFor(StringVariableReplica svr) {
        AString shat = this.map.get(svr);
        return shat == null ? AString.makeStringBottom(MAX_STRING_SET_SIZE) : shat;
    }

    public StringConstraintDelta joinAt(StringVariableReplica svr, AString shat) {
        if (this.map.containsKey(svr)) {
            boolean changedp = this.map.get(svr).join(shat);
            return changedp ? StringConstraintDelta.make(this, svr) : StringConstraintDelta.makeEmpty(this);
        }
        else {
            this.map.put(svr, shat);
            return StringConstraintDelta.make(this, svr);
        }
    }

    public StringConstraintDelta upperBounds(StringVariableReplica svr1, StringVariableReplica svr2) {
        if (this.map.containsKey(svr2)) {
            return this.joinAt(svr1, this.map.get(svr2));
        } else {
            return StringConstraintDelta.makeEmpty(this);
        }
    }

    /*
     * AUTOGENERATED STUFF
     *
     * Be sure to regenerate these (using Eclipse) if you change the number of fields in this class
     *
     * DEFINTIELY DONT CHANGE ANYTHING
     */

    @Override
    public String toString() {
        return "StringConstraints [map=" + map + "]";
    }
}