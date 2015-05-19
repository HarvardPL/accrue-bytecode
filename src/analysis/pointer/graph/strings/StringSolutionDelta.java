package analysis.pointer.graph.strings;

import java.util.HashSet;
import java.util.Set;

public class StringSolutionDelta {

    private final StringSolution sc;
    private Set<StringLikeLocationReplica> newlyActivated;
    private Set<StringLikeLocationReplica> updated;

    /* Factory Methods */

    public static final StringSolutionDelta makeEmpty(StringSolution sc) {
        return new StringSolutionDelta(sc);
    }

    /* Constructors */

    public StringSolutionDelta(StringSolution sc) {
        this.sc = sc;
        this.newlyActivated = new HashSet<>();
        this.updated = new HashSet<>();
    }

    /* Logic */

    public boolean isEmpty() {
        return this.newlyActivated.isEmpty() && this.updated.isEmpty();
    }

    public void combine(StringSolutionDelta that) {
        assert this.sc == that.sc;
        this.newlyActivated.addAll(that.newlyActivated);
        this.updated.addAll(that.updated);
    }

    public void addUpdated(StringLikeLocationReplica updatedVar) {
        this.updated.add(updatedVar);

    }

    public void addNewlyActivated(StringLikeLocationReplica newlyActivatedVar) {
        this.newlyActivated.add(newlyActivatedVar);
    }

    public Set<StringLikeLocationReplica> getNewlyActivated() {
        return this.newlyActivated;
    }

    public Set<StringLikeLocationReplica> getUpdated() {
        return this.updated;
    }

    @Override
    public String toString() {
        return "StringConstraintDelta [newlyActivated=" + this.newlyActivated + ", updated=" + this.updated + "]";
    }

}
