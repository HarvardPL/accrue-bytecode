package analysis.dataflow.interprocedural.pdg.serialization;

import analysis.dataflow.interprocedural.pdg.graph.node.AbstractLocationPDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.ProcedurePDGNode;

public enum PDGNodeClassName {
    /**
     * {@link AbstractLocationPDGNode}
     */
    ABSTRACT_LOCATION,
    /**
     * {@link ProcedurePDGNode}
     */
    EXPR;
}
