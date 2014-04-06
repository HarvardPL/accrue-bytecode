package analysis.dataflow.interprocedural;

import analysis.dataflow.DataFlow;

/**
 * Inter-procedural extension of a data-flow analysis
 * 
 * @param <FlowItem>
 *            Type of the data-flow facts
 */
public abstract class InterproceduralDataFlow<FlowItem> {

    /**
     * Intra-procedural data-flow this extends
     */
    private final DataFlow<FlowItem> df;

    /**
     * Create a new Interprocedural data-flow
     * 
     * @param df
     *            intra-procedural data-flow this extends
     */
    public InterproceduralDataFlow(DataFlow<FlowItem> df) {
        this.df = df;
    }
}
