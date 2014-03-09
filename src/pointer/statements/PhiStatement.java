package pointer.statements;

import java.util.List;

import pointer.LocalNode;
import pointer.PointsToGraph;
import pointer.PointsToGraphNode;
import pointer.ReferenceVariableReplica;
import pointer.analyses.HeapAbstractionFactory;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

/**
 * Points to graph statement for a phi, representing choice at control flow
 * merges. v = phi(x1, x2, ...)
 */
public class PhiStatement implements PointsToStatement {

    /**
     * Value assigned into
     */
    private final LocalNode assignee;
    /**
     * Arguments into the phi
     */
    private final List<LocalNode> uses;
    /**
     * Type of the value assigned into
     */
    private final TypeReference type;

    /**
     * Points to graph statement for a phi, v = phi(xs[1], xs[2], ...)
     * 
     * @param v
     *            value assigned into
     * @param xs
     *            list of arguments to the phi, v is a choice amongst these
     */
    public PhiStatement(LocalNode v, List<LocalNode> xs) {
        this.assignee = v;
        this.uses = xs;
        this.type = v.getExpectedType();
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {

        PointsToGraphNode a = new ReferenceVariableReplica(context, assignee);
        boolean changed = false;

        // For every possible branch add edges into assignee
        for (LocalNode use : uses) {
            PointsToGraphNode n = new ReferenceVariableReplica(context, use);
            changed |= g.addEdges(a, g.getPointsToSetFiltered(n, assignee.getExpectedType()));
        }
        return changed;
    }

    @Override
    public TypeReference getExpectedType() {
        return type;
    }

}
