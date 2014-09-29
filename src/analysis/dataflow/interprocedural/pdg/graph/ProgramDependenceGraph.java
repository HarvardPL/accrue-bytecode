package analysis.dataflow.interprocedural.pdg.graph;

import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.AnalysisResults;
import analysis.dataflow.interprocedural.pdg.graph.node.AbstractLocationPDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNodeType;
import analysis.dataflow.interprocedural.pdg.graph.node.ProcedurePDGNode;
import analysis.dataflow.interprocedural.pdg.serialization.JSONSerializable;
import analysis.dataflow.interprocedural.pdg.serialization.JSONUtil;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;

/**
 * Program Dependence Graph combining control flow and data-flow dependency information between entities
 */
public class ProgramDependenceGraph implements AnalysisResults, JSONSerializable {

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
        nodes = new LinkedHashSet<>();
        edges = new LinkedHashMap<>();
    }

    /**
     * Add an edge to the PDG
     *
     * @param source
     *            source of the edge (non-null)
     * @param target
     *            target of the edge (non-null)
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
        assert source != null : "Null source for edge to " + target + " of type " + type;
        assert target != null : "Null target for edge from " + source + " of type " + type;
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
    public void printDetailedCounts() {
        printSimpleCounts();
        String result = "";
        for (PDGEdgeType t : edges.keySet()) {
            result += edges.get(t).size() + " edges of type " + t + "\n";
        }
        Map<PDGNodeType, Integer> nodeCounts = new LinkedHashMap<>();
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
     * Print the number of nodes and edges in the PDG
     */
    public void printSimpleCounts() {
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
     *            if true then the graph will contain subgraphs for each procedure, one for the heap, and other
     *            subgraphs for each
     * @param spread
     *            Separation between nodes in inches different
     * @throws IOException
     *             writer issues
     */
    public void writeDot(Writer writer, boolean cluster, double spread) throws IOException {
        Set<PDGEdge> edgeSet = new LinkedHashSet<>();
        for (PDGEdgeType t : edges.keySet()) {
            edgeSet.addAll(edges.get(t));
        }

        writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
                                        + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n"
                                        + "edge [fontsize=10]" + ";\n");
        Map<PDGNode, String> nodeToDot = new LinkedHashMap<>();
        Map<String, Integer> dotToCount = new LinkedHashMap<>();
        Map<String, Set<PDGNode>> analysisUnitToNodes = new LinkedHashMap<>();

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
                    nodesInContext = new LinkedHashSet<>();
                    analysisUnitToNodes.put(groupName, nodesInContext);
                }
                nodesInContext.add(n);
            }
        }
        if (cluster) {
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

    /**
     * Write the PDG for the given method name in graphviz dot format
     *
     * @param spread Separation between nodes in inches different
     *
     * @throws IOException writer issues
     */
    public void intraProcDotToFile(double spread, String methodName) throws IOException {
        Set<PDGEdge> edgeSet = new LinkedHashSet<>();
        for (PDGEdgeType t : edges.keySet()) {
            edgeSet.addAll(edges.get(t));
        }

        Map<PDGNode, String> nodeToDot = new LinkedHashMap<>();
        Map<String, Integer> dotToCount = new LinkedHashMap<>();
        Map<CGNode, Set<PDGNode>> cgNodeToNodes = new LinkedHashMap<>();
        Map<CGNode, Set<PDGEdge>> cgNodeToEdges = new LinkedHashMap<>();
        // Nodes from other CGNodes that touch nodes from the key CGNode
        Map<CGNode, Map<CGNode, Set<PDGNode>>> auxNodes = new LinkedHashMap<>();
        Map<CGNode, Set<AbstractLocationPDGNode>> heapNodes = new LinkedHashMap<>();

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

            if (n instanceof ProcedurePDGNode) {
                CGNode cg = ((ProcedurePDGNode) n).getCGNode();
                Set<PDGNode> nodesForCG = cgNodeToNodes.get(cg);
                if (nodesForCG == null) {
                    nodesForCG = new LinkedHashSet<>();
                    cgNodeToNodes.put(cg, nodesForCG);
                }
                nodesForCG.add(n);
            }
        }

        for (PDGEdge e : edgeSet) {
            if (e.source instanceof ProcedurePDGNode && e.target instanceof ProcedurePDGNode) {
                CGNode cg1 = ((ProcedurePDGNode) e.source).getCGNode();
                CGNode cg2 = ((ProcedurePDGNode) e.target).getCGNode();
                Set<PDGEdge> es1 = cgNodeToEdges.get(cg1);
                if (es1 == null) {
                    es1 = new LinkedHashSet<>();
                    cgNodeToEdges.put(cg1, es1);
                }
                es1.add(e);

                Set<PDGEdge> es2 = cgNodeToEdges.get(cg2);
                if (es2 == null) {
                    es2 = new LinkedHashSet<>();
                    cgNodeToEdges.put(cg2, es2);
                }
                es2.add(e);

                if (!cg1.equals(cg2)) {
                    // Add to the aux node set for cg1
                    Map<CGNode, Set<PDGNode>> auxNodes1 = auxNodes.get(cg1);
                    if (auxNodes1 == null) {
                        auxNodes1 = new LinkedHashMap<>();
                        auxNodes.put(cg1, auxNodes1);
                    }
                    Set<PDGNode> setAux1 = auxNodes1.get(cg2);
                    if (setAux1 == null) {
                        setAux1 = new LinkedHashSet<>();
                        auxNodes1.put(cg2, setAux1);
                    }
                    setAux1.add(e.target);

                    // Add to the aux node set for cg2
                    Map<CGNode, Set<PDGNode>> auxNodes2 = auxNodes.get(cg2);
                    if (auxNodes2 == null) {
                        auxNodes2 = new LinkedHashMap<>();
                        auxNodes.put(cg2, auxNodes2);
                    }
                    Set<PDGNode> setAux2 = auxNodes2.get(cg1);
                    if (setAux2 == null) {
                        setAux2 = new LinkedHashSet<>();
                        auxNodes2.put(cg1, setAux2);
                    }
                    setAux2.add(e.source);

                }
            }
            else {
                // either the source or target is a HEAP node
                if (e.source instanceof ProcedurePDGNode && e.target instanceof AbstractLocationPDGNode) {
                    CGNode cg1 = ((ProcedurePDGNode) e.source).getCGNode();
                    Set<PDGEdge> es1 = cgNodeToEdges.get(cg1);
                    if (es1 == null) {
                        es1 = new LinkedHashSet<>();
                        cgNodeToEdges.put(cg1, es1);
                    }
                    es1.add(e);

                    Set<AbstractLocationPDGNode> heap = heapNodes.get(cg1);
                    if (heap == null) {
                        heap = new LinkedHashSet<>();
                        heapNodes.put(cg1, heap);
                    }
                    heap.add((AbstractLocationPDGNode) e.target);
                }

                if (e.source instanceof AbstractLocationPDGNode && e.target instanceof ProcedurePDGNode) {
                    CGNode cg2 = ((ProcedurePDGNode) e.target).getCGNode();
                    Set<PDGEdge> es1 = cgNodeToEdges.get(cg2);
                    if (es1 == null) {
                        es1 = new LinkedHashSet<>();
                        cgNodeToEdges.put(cg2, es1);
                    }
                    es1.add(e);

                    Set<AbstractLocationPDGNode> heap = heapNodes.get(cg2);
                    if (heap == null) {
                        heap = new LinkedHashSet<>();
                        heapNodes.put(cg2, heap);
                    }
                    heap.add((AbstractLocationPDGNode) e.source);
                }
            }
        }

        Set<IMethod> visited = new HashSet<>();
        for (CGNode cg : cgNodeToNodes.keySet()) {
            if (!PrettyPrinter.methodString(cg.getMethod()).contains(methodName)) {
                // This is not the method we are looking for
                continue;
            }
            if (visited.contains(cg.getMethod())) {
                // Different methods should be identical
                continue;
            }
            String fileName = "tests/pdg_" + PrettyPrinter.methodString(cg.getMethod()) + ".dot";
            try (Writer writer = new FileWriter(fileName)) {
                String label = fileName.replace("\"", "").replace("\\", "\\\\");
                writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
                                                + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n"
                                                + "edge [fontsize=10]" + ";\n" + "label=\"" + label + "\";\n");

                String mainClusterLabel = PrettyPrinter.methodString(cg.getMethod())
                                                       .replace("\"", "")
                                                       .replace("\\", "\\\\");
                writer.write("\tsubgraph \"cluster_" + mainClusterLabel + "\"{\n");
                writer.write("\tlabel=\"" + mainClusterLabel + "\";\n");
                for (PDGNode n : cgNodeToNodes.get(cg)) {
                    String nodeLabel = "";
                    if (n.getNodeType().isPathCondition()) {
                        nodeLabel = "[style=filled, fillcolor=gray95]";
                    }
                    writer.write("\t\t\"" + nodeToDot.get(n) + "\" " + nodeLabel + "\n");
                }
                writer.write("\t}\n"); // subgraph close

                Map<CGNode, Set<PDGNode>> aux = auxNodes.get(cg);
                if (aux != null) {
                    for (CGNode cg2 : aux.keySet()) {

                        String clusterLabel = PrettyPrinter.methodString(cg2.getMethod())
                                                           .replace("\"", "")
                                                           .replace("\\", "\\\\");
                        writer.write("\tsubgraph \"cluster_" + clusterLabel + "\"{\n");
                        writer.write("\tlabel=\"" + clusterLabel + "\";\n");
                        for (PDGNode n : aux.get(cg2)) {
                            String nodeLabel = "";
                            if (n.getNodeType().isPathCondition()) {
                                nodeLabel = "[style=filled, fillcolor=gray95]";
                            }
                            writer.write("\t\t\"" + nodeToDot.get(n) + "\" " + nodeLabel + "\n");
                        }
                        writer.write("\t}\n"); // subgraph close
                    }
                }

                if (cgNodeToEdges.get(cg) != null) {
                    for (PDGEdge edge : cgNodeToEdges.get(cg)) {
                        PDGEdgeType type = edge.type;
                        String edgeLabel = "[label=\"" + type.shortName()
                                                        + (edge.label != null ? " " + edge.label : "") + "\"]";
                        writer.write("\t\"" + nodeToDot.get(edge.source) + "\" -> " + "\"" + nodeToDot.get(edge.target)
                                                        + "\" " + edgeLabel + ";\n");
                    }
                }

                writer.write("\n};\n");
            }
            System.err.println("DOT written to " + fileName);
        }
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
    private static class PDGEdge implements JSONSerializable {

        /**
         * source of the edge
         */
        final PDGNode source;
        /**
         * target of the edge
         */
        final PDGNode target;
        /**
         * type of the edge
         */
        final PDGEdgeType type;
        /**
         * label of the call site one of these nodes is a summary for, null if this is not an edge to/from a summary
         * node
         */
        final CallSiteEdgeLabel label;

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
            return source + " -> " + target + " [" + type + "]" + (label != null ? (" [" + label + "]") : "");
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
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            PDGEdge other = (PDGEdge) obj;
            if (label == null) {
                if (other.label != null) {
                    return false;
                }
            } else if (!label.equals(other.label)) {
                return false;
            }
            if (source == null) {
                if (other.source != null) {
                    return false;
                }
            } else if (!source.equals(other.source)) {
                return false;
            }
            if (target == null) {
                if (other.target != null) {
                    return false;
                }
            } else if (!target.equals(other.target)) {
                return false;
            }
            if (type != other.type) {
                return false;
            }
            return true;
        }

        @Override
        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            try {
                json.put("source", JSONUtil.getNodeID(source));
                json.put("dest", JSONUtil.getNodeID(target));
                json.put("type", type.toString());
                JSONUtil.addJSON(json, label);
            } catch (JSONException e) {
                throw new RuntimeException("Serialization error for PDGEdge " + this + ", message: " + e.getMessage());
            }
            return json;
        }

        @Override
        public void writeJSON(Writer out) throws JSONException {
            toJSON().write(out);
        }
    }

    @Override
    public JSONObject toJSON() {
        try (StringWriter sw = new StringWriter()) {
            writeJSON(sw);
            JSONObject json = new JSONObject(sw.toString());
            return json;
        } catch (JSONException | IOException e) {
            System.err.println("Cannot write JSON: " + e);
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void writeJSON(Writer out) throws JSONException {
        try {
            out.write('{');
            out.write(" nodes:");
            out.write("[");
            boolean first = true;
            for (PDGNode n : this.nodes) {
                if (first) {
                    first = false;
                } else {
                    out.write(", ");
                }
                n.writeJSON(out);
            }

            out.write("]");
            out.write(",\n  edges:");
            out.write("[");
            first = true;
            for (PDGEdgeType t : this.edges.keySet()) {
                for (PDGEdge e : edges.get(t)) {
                    if (first) {
                        first = false;
                    } else {
                        out.write(", ");
                    }
                    e.writeJSON(out);
                }
            }
            out.write("]");
            out.write("\n}");
        } catch (IOException e) {
            throw new JSONException(e);
        }
    }

}
