package analysis.pointer.engine;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.PointsToStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to analysis engine
 */
public abstract class PointsToAnalysis {

    /**
     * If true then debug strings may be more verbose, and memory usage may be higher, no additional output should be
     * produced. Adjust {@link PointsToAnalysis#outputLevel} for more verbose console output.
     */
    public static final boolean DEBUG = false;
    /**
     * Defining abstraction factory for this points-to analysis
     */
    protected final RecencyHeapAbstractionFactory haf;

    /**
     * Effects the amount of debugging output. See also {@link PointsToAnalysis#DEBUG}, which makes debug strings in
     * reference variables and allocation nodes more verbose.
     */
    public static int outputLevel = 0;
    /**
     * Flag for debugging the analysis by rerunning all the statements and looking for inconsistencies
     */
    public static boolean paranoidMode;
    /**
     * Time the pointer analysis started
     */
    public static long startTime;


    /**
     * Create a new analysis with the given abstraction
     */
    public PointsToAnalysis(HeapAbstractionFactory haf) {
        this.haf = new RecencyHeapAbstractionFactory(haf);
    }

    public RecencyHeapAbstractionFactory heapAbstractionFactory() {
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

    /**
     * Loop through and process all the points-to statements in the registrar.
     *
     * @param g points-to graph (may be modified)
     * @param registrar points-to statement registrar
     * @return true if the points-to graph changed
     */
    protected boolean processAllStatements(PointsToGraph g, StatementRegistrar registrar) {
        boolean changed = false;
        System.err.println("Processing all statements for good luck: " + registrar.size() + " from "
                + registrar.getRegisteredMethods().size() + " methods");
        int failcount = 0;
        int successcount = 0;
        for (IMethod m : registrar.getRegisteredMethods()) {
            for (PointsToStatement s : registrar.getStatementsForMethod(m)) {
                for (Context c : g.getContexts(s.getMethod())) {
                    GraphDelta d = s.process(c, this.haf, g, null, registrar, new StmtAndContext(s, c));
                    if (d == null) {
                        throw new RuntimeException("s returned null " + s.getClass() + " : " + s);
                    }
                    changed |= !d.isEmpty();
                    if (!d.isEmpty()) {
                        System.err.println("uhoh Failed on (" + s.getClass() + ")" + s + "\n    Delta is " + d);
                        failcount++;
                        if (failcount > 10) {
                            System.err.println("\nThere may be more failures, but exiting now...");
                            System.exit(1);
                        }
                    }
                    else {
                        successcount++;
                        if (successcount % 1000 == 0) {
                            System.err.println("PROCESSED " + successcount);
                        }
                    }
                }
            }
        }
        return changed;
    }
}
