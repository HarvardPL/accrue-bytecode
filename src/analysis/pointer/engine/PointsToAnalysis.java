package analysis.pointer.engine;

import java.util.Collection;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.PointsToStatement;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * Points-to analysis engine
 */
public abstract class PointsToAnalysis<IK extends InstanceKey, C extends Context> {

    /**
     * If true then debug strings may be more verbose, and memory usage may be higher, no additional output should be
     * produced. Adjust {@link PointsToAnalysis#outputLevel} for more verbose console output.
     */
    public static boolean DEBUG = false;
    /**
     * Defining abstraction factory for this points-to analysis
     */
    protected final HeapAbstractionFactory<IK, C> haf;

    /**
     * Effects the amount of debugging output. See also {@link PointsToAnalysis#DEBUG}, which makes debug strings in
     * reference variables and allocation nodes more verbose.
     */
    public static int outputLevel = 0;


    /**
     * Create a new analysis with the given abstraction
     */
    public PointsToAnalysis(HeapAbstractionFactory<IK, C> haf) {
        this.haf = haf;
    }

    public HeapAbstractionFactory<IK, C> heapAbstractionFactory() {
        return haf;
    }

    /**
     * Perform the analysis using the constraints from the points-to statement registrar
     *
     * @param registrar
     *            points-to statements
     *
     * @return points-to graph consistent with the statements in the registrar
     */
    public abstract PointsToGraph solve(StatementRegistrar registrar);

    /**
     * Points-to statement together with a code context
     */
    public static class StmtAndContext {
        /**
         * Code context
         */
        final Context context;
        /**
         * Points-to statement
         */
        final PointsToStatement stmt;

        /**
         * Combination of a points-to statement and a code context
         *
         * @param stmt
         *            points-to statement
         * @param context
         *            code context
         */
        public StmtAndContext(PointsToStatement stmt, Context context) {
            this.stmt = stmt;
            this.context = context;
        }

        @SuppressWarnings("unchecked")
        public <IK extends InstanceKey, C extends Context> Collection<?> getReadDependencies(HeapAbstractionFactory<IK, C> haf) {
            return this.stmt.getReadDependencies((C) this.context, haf);
        }

        @SuppressWarnings("unchecked")
        public <IK extends InstanceKey, C extends Context> Collection<?> getWriteDependencies(HeapAbstractionFactory<IK, C> haf) {
            return this.stmt.getWriteDependencies((C) this.context, haf);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result =
                    prime * result + (this.context == null ? 0 : this.context.hashCode());
            result = prime * result + (this.stmt == null ? 0 : this.stmt.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (this.getClass() != obj.getClass()) {
                return false;
            }
            StmtAndContext other = (StmtAndContext) obj;
            if (this.context == null) {
                if (other.context != null) {
                    return false;
                }
            }
            else if (!this.context.equals(other.context)) {
                return false;
            }
            if (this.stmt == null) {
                if (other.stmt != null) {
                    return false;
                }
            }
            else if (!this.stmt.equals(other.stmt)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return this.stmt + " in " + this.context;
        }
    }

    abstract public PointsToGraph solveAndRegister(StatementRegistrar registrar);
}
