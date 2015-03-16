package analysis.pointer.graph;

import java.util.Collections;
import java.util.concurrent.ConcurrentMap;

import analysis.AnalysisUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.ReflectiveHAF;

public class StringConstraints {

    private final ConcurrentMap<StringVariableReplica, AString> map;
    private final ReflectiveHAF haf;
    private final AString initialString;

    /* Factory Methods */

    public static StringConstraints make(ReflectiveHAF haf) {
        return new StringConstraints(haf);
    }

    /* Constructors */

    private StringConstraints(ReflectiveHAF haf) {
        this.haf = haf;
        this.initialString = haf.getAStringSet(Collections.singleton(""));
        this.map = AnalysisUtil.createConcurrentHashMap();
    }

    /* Logic */

    public AString getAStringFor(StringVariableReplica svr) {
        AString shat = this.map.get(svr);
        return shat == null ? haf.getAStringBottom() : shat;
    }

    public StringConstraintDelta joinAt(StringVariableReplica svr, AString shat) {
        if (this.map.containsKey(svr)) {
            boolean changedp = this.map.get(svr).join(shat);
            return changedp ? StringConstraintDelta.make(this, svr) : StringConstraintDelta.makeEmpty(this);
        }
        else {
            this.map.put(svr, shat.copy());
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
