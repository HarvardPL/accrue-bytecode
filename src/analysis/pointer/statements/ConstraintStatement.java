package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public interface ConstraintStatement {

    /**
     * Process this statement, modifying the points-to graph if necessary
     *
     * @param context current analysis context
     * @param haf factory for creating new analysis contexts
     * @param g points-to graph (may be modified)
     * @param delta Changes to the graph relevant to this statement since the last time this stmt was processed. Maybe
     *            null (e.g., if it is the first time the statement is processed, and may be used by the processing to
     *            improve the performance of processing).
     * @param registrar Points-to statement registrar
     * @param originator TODO
     * @param originator The SaC that caused this processing, i.e. the pair of this and context.
     * @return Changes to the graph as a result of processing this statement. Must be non-null.
     */
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator);

    public IMethod getMethod();

}
