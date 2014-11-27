package analysis.dataflow.interprocedural.pdg.serialization;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import analysis.dataflow.interprocedural.pdg.graph.CallSiteEdgeLabel;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;

public class JSONUtil {

    /**
     * Map from node to unique integer
     */
    private static final Map<PDGNode, Long> idMap = new HashMap<>();

    /**
     * Counter used to ensure every node gets a unique integer
     */
    private static long nodeCounter;

    /**
     * Get the unique identifier for this pdg node
     *
     * @param n
     *            node
     * @return integer unique to that node
     */
    public static long getNodeID(PDGNode n) {
        Long i = idMap.get(n);
        if (i == null) {
            i = nodeCounter++;
            idMap.put(n, i);
        }
        return i;
    }

    /**
     * Serialize the given {@link PDGNode}
     *
     * @param n
     *            node to serialize
     * @return {@link JSONObject} containing the serialized form
     */
    public static JSONObject toJSON(PDGNode n) {
        JSONObject json = new JSONObject();
        try {
            addJSON(json, "class", n.getClassName().toString());
            // TODO could try to get line numbers here
            // See pdg-polices for "position" format
            json.put("position", JSONObject.NULL);
            json.put("nodeid", getNodeID(n));
            addJSON(json, "name", n.toString());
            addJSON(json, "type", n.getNodeType().toString());
            addJSON(json, "group", n.groupingName());
            addJSON(json, "context", n.contextString());
        } catch (JSONException e) {
            System.err.println("Serialization error in " + n.toString() + ", message: " + e.getMessage());
        }
        return json;
    }

    /**
     * Add an edge label to the given {@link JSONObject}. The existing {@link JSONObject} will be modified
     *
     * @param json
     *            The edge label will be added to this
     * @param label
     *            {@link EdgeLabel} to add
     */
    public static void addJSON(JSONObject json, CallSiteEdgeLabel label) {
        // do not add anything if the label is null
        // need to handle this at the other end
        if (label == null) {
            return;
        }
        try {
            JSONObject labelJson = new JSONObject();
            addJSON(labelJson, "type", label.getType().toString());
            labelJson.put("id", label.getCallSiteID().intValue());
            if (label.getReceiverIDs() != null) {
                labelJson.put("receivers", label.getReceiverIDs());
            }
            json.put("label", labelJson);
        } catch (JSONException e) {
            System.err.println("Serialization error for EdgeLabel: " + label + ", message: " + e.getMessage());
        }
    }

    /**
     * Add the (key,value) pair to the given JSONObject. The existing {@link JSONObject} will be modified.
     *
     * @param json
     *            the (key,value) pair will be added to this
     * @param key
     *            key for the new JSON entry
     * @param value
     *            string value to add to the JSON object
     */
    public static void addJSON(JSONObject json, String key, String value) {
        try {
            json.put(key, value);
        } catch (JSONException e) {
            System.err.println("Serialization error for (" + key + ", " + value + "), message: " + e.getMessage());
        }
    }

    /**
     * Add the (key,value) pair to the given JSONObject. The existing {@link JSONObject} will be modified.
     *
     * @param json
     *            the (key,value) pair will be added to this
     * @param key
     *            key for the new JSON entry
     * @param value
     *            boolean value to add to the JSON object
     */
    public static void addJSON(JSONObject json, String key, boolean value) {
        try {
            json.put(key, value);
        } catch (JSONException e) {
            System.err.println("Serialization error for (" + key + ", " + value + "), message: " + e.getMessage());
        }
    }
}
