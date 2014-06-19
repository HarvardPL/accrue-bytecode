package analysis.dataflow.interprocedural.pdg.serialization;

import java.io.Writer;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Implementing classes are serializable via JSON (JavaScript Object Notation)
 */
public interface JSONSerializable {

    /**
     * Create a JSON object from this Java object
     * 
     * @return The JSON object corresponding to <code>this</code>
     */
    public JSONObject toJSON();

    /**
     * Write this object to out.
     * 
     * @param out
     *            writer to write to
     * @throws JSONException
     *             error writing to JSON
     */
    public void writeJSON(Writer out) throws JSONException;

}
