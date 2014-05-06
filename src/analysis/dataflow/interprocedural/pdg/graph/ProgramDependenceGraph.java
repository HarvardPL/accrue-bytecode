package analysis.dataflow.interprocedural.pdg.graph;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import analysis.dataflow.interprocedural.AnalysisResults;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeType;

/**
 * Graph combining control flow and data-flow dependency information between
 * entities
 */
public class ProgramDependenceGraph implements AnalysisResults {

    /**
     * All nodes in the PDG
     */
    private final Set<PDGNode> nodes;
    /**
     * All edges in the PDG
     */
    private final Map<PDGEdgeType, Set<PDGEdge>> edges;

    /**
     * Create a new program dependence graph
     */
    public ProgramDependenceGraph() {
        nodes = new LinkedHashSet<PDGNode>();
        edges = new LinkedHashMap<PDGEdgeType, Set<PDGEdge>>();
    }

    /**
     * Add an edge to the PDG
     * 
     * @param source
     *            source of the edge
     * @param target
     *            target of the edge
     * @param type
     *            type of edge
     */
    public void addEdge(PDGNode source, PDGNode target, PDGEdgeType type) {
        addEdge(source, target, type, null);
    }

    /**
     * Add an edge to the PDG (to/from a summary node)
     * 
     * @param source
     *            source of the edge
     * @param target
     *            target of the edge
     * @param type
     *            type of edge
     * @param label
     *            label of the call site one of these nodes is a summary for
     */
    public void addEdge(PDGNode source, PDGNode target, PDGEdgeType type, CallSiteEdgeLabel label) {
        nodes.add(source);
        nodes.add(target);
        Set<PDGEdge> edgesForType = edges.get(type);
        if (edgesForType == null) {
            edgesForType = new LinkedHashSet<>();
            edges.put(type, edgesForType);
        }
        edgesForType.add(new PDGEdge(source, target, type, label));
    }

    /**
     * Get the number of edges in the PDG
     * 
     * @return number of edges
     */
    public int numEdges() {
        int num = 0;
        for (PDGEdgeType t : edges.keySet()) {
            num += edges.get(t).size();
        }
        return num;
    }

    /**
     * Get the number of nodes in the PDG
     * 
     * @return number of nodes
     */
    public int numNodes() {
        return nodes.size();
    }

    /**
     * Print some diagnostic information about the PDG
     */
    public void printCounts() {
        String result = "";
        for (PDGEdgeType t : edges.keySet()) {
            result += edges.get(t).size() + " edges of type " + t + "\n";
        }
        Map<PDGNodeType, Integer> nodeCounts = new LinkedHashMap<PDGNodeType, Integer>();
        for (PDGNode n : nodes) {
            Integer count = nodeCounts.get(n.getNodeType());
            if (count == null) {
                count = 0;
            }
            nodeCounts.put(n.getNodeType(), ++count);
        }
        for (PDGNodeType t : nodeCounts.keySet()) {
            result += nodeCounts.get(t) + " nodes of type " + t + "\n";
        }
        System.err.println(result);
    }

    /**
     * Print the stats for a graph
     */
    public void printStats() {
        String result = "";
        result += numNodes() + " nodes\n";
        result += numEdges() + " edges\n";
        System.err.println(result);
    }

    /**
     * Write the graph in graphviz dot format
     * 
     * @param writer
     *            writer to write to
     * @param cluster
     *            if true then the graph will contain subgraphs for each
     *            procedure, one for the heap, and other subgraphs for each
     * @param spread
     *            Separation between nodes in inches different
     * @throws IOException
     *             writer issues
     */
    public void writeDot(Writer writer, boolean cluster, double spread) throws IOException {
        Set<PDGEdge> edgeSet = new LinkedHashSet<PDGEdge>();
        for (PDGEdgeType t : edges.keySet()) {
            edgeSet.addAll(edges.get(t));
        }

        writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
                                        + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n"
                                        + "edge [fontsize=10]" + ";\n");
        Map<PDGNode, String> nodeToDot = new LinkedHashMap<PDGNode, String>();
        Map<String, Integer> dotToCount = new LinkedHashMap<String, Integer>();
        Map<String, Set<PDGNode>> analysisUnitToNodes = new LinkedHashMap<String, Set<PDGNode>>();
        Set<PDGNode> heapNodes = new LinkedHashSet<PDGNode>();

        for (PDGNode n : nodes) {
            String nodeString = n.toString().replace("\"", "").replace("\\", "\\\\").replace("\\\\n", "(newline)")
                                            .replace("\\\\t", "(tab)");
            Integer count = dotToCount.get(nodeString);
            if (count == null) {
                nodeToDot.put(n, nodeString);
                dotToCount.put(nodeString, 1);
            } else if (nodeToDot.get(n) == null) {
                nodeToDot.put(n, nodeString + " (" + count + ")");
                dotToCount.put(nodeString, count + 1);
            }

            String groupName = n.groupingName();
            if (groupName != null) {
                Set<PDGNode> nodesInContext = analysisUnitToNodes.get(groupName);
                if (nodesInContext == null) {
                    nodesInContext = new LinkedHashSet<PDGNode>();
                    analysisUnitToNodes.put(groupName, nodesInContext);
                }
                nodesInContext.add(n);
            }
        }
        if (cluster) {
            if (!heapNodes.isEmpty()) {
                writer.write("\tsubgraph \"cluster_" + "HEAP" + "\"{\n");
                writer.write("\tlabel=\"" + "HEAP" + "\";\n");
                for (PDGNode n : heapNodes) {
                    writer.write("\t\t\"" + nodeToDot.get(n) + "\"\n");
                }
                writer.write("\t}\n"); // subgraph close
            }

            for (String c : analysisUnitToNodes.keySet()) {
                String label = c.replace("\"", "").replace("\\", "\\\\");
                writer.write("\tsubgraph \"cluster_" + label + "\"{\n");
                writer.write("\tlabel=\"" + label + "\";\n");
                for (PDGNode n : analysisUnitToNodes.get(c)) {
                    String nodeLabel = "";
                    if (n.getNodeType().isPathCondition()) {
                        nodeLabel = "[style=filled, fillcolor=gray95]";
                    }
                    writer.write("\t\t\"" + nodeToDot.get(n) + "\" " + nodeLabel + "\n");
                }
                writer.write("\t}\n"); // subgraph close
            }
        } else {
            for (PDGNode n : nodes) {
                String nodeLabel = "";
                if (n.getNodeType().isPathCondition()) {
                    nodeLabel = "[style=filled, fillcolor=gray95]";
                }
                writer.write("\t\"" + nodeToDot.get(n) + "\" " + nodeLabel + "\n");
            }
        }

        for (PDGEdge edge : allEdges()) {
            PDGEdgeType type = edge.type;
            String edgeLabel = "[label=\"" + type.shortName() + (edge.label != null ? " " + edge.label : "") + "\"]";
            writer.write("\t\"" + nodeToDot.get(edge.source) + "\" -> " + "\"" + nodeToDot.get(edge.target) + "\" "
                                            + edgeLabel + ";\n");
        }

        writer.write("\n};\n");
    }

    private Set<PDGEdge> allEdges() {
        Set<PDGEdge> all = new LinkedHashSet<>();
        for (PDGEdgeType t : edges.keySet()) {
            all.addAll(edges.get(t));
        }
        return all;
    }

    /**
     * Directed edge in the PDG
     */
    private static class PDGEdge {

        /**
         * source of the edge
         */
        private final PDGNode source;
        /**
         * target of the edge
         */
        private final PDGNode target;
        /**
         * type of the edge
         */
        private final PDGEdgeType type;
        /**
         * label of the call site one of these nodes is a summary for, null if
         * this is not an edge to/from a summary node
         */
        private final CallSiteEdgeLabel label;

        /**
         * Create an edge to the PDG (to/from a summary node)
         * 
         * @param source
         *            source of the edge
         * @param target
         *            target of the edge
         * @param type
         *            type of edge
         * @param label
         *            label of the call site one of these nodes is a summary for
         */
        public PDGEdge(PDGNode source, PDGNode target, PDGEdgeType type, CallSiteEdgeLabel label) {
            this.source = source;
            this.target = target;
            this.type = type;
            this.label = label;
        }

        @Override
        public String toString() {
            return source + " -> " + target + " [" + type + "]" + label != null ? (" [" + label + "]") : "";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((label == null) ? 0 : label.hashCode());
            result = prime * result + ((source == null) ? 0 : source.hashCode());
            result = prime * result + ((target == null) ? 0 : target.hashCode());
            result = prime * result + ((type == null) ? 0 : type.hashCode());
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
            PDGEdge other = (PDGEdge) obj;
            if (label == null) {
                if (other.label != null)
                    return false;
            } else if (!label.equals(other.label))
                return false;
            if (source == null) {
                if (other.source != null)
                    return false;
            } else if (!source.equals(other.source))
                return false;
            if (target == null) {
                if (other.target != null)
                    return false;
            } else if (!target.equals(other.target))
                return false;
            if (type != other.type)
                return false;
            return true;
        }
    }
}
