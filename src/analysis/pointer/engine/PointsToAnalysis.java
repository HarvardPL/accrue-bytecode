package analysis.pointer.engine;

import util.WalaAnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.StatementRegistrar;

import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to analysis engine
 */
public abstract class PointsToAnalysis {

    /**
     * Defining abstraction factory for this points-to analysis
     */
    protected final HeapAbstractionFactory haf;
    /**
     * Class hierarchy and other analysis utilities
     */
    protected final WalaAnalysisUtil util;

    /**
     * Create a new analysis with the given abstraction
     */
    public PointsToAnalysis(HeapAbstractionFactory haf, WalaAnalysisUtil util) {
        this.haf = haf;
        this.util = util;
    }

    public abstract PointsToGraph solve(StatementRegistrar registrar);

    /**
     * Points-to statement together with a code context
     */
    protected static class StmtAndContext {
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

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((context == null) ? 0 : context.hashCode());
            result = prime * result + ((stmt == null) ? 0 : stmt.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            StmtAndContext other = (StmtAndContext) obj;
            if (context == null) {
                if (other.context != null)
                    return false;
            } else if (!context.equals(other.context))
                return false;
            if (stmt == null) {
                if (other.stmt != null)
                    return false;
            } else if (!stmt.equals(other.stmt))
                return false;
            return true;
        }

    }
}
