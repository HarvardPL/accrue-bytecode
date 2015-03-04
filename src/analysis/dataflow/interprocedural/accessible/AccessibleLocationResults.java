package analysis.dataflow.interprocedural.accessible;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import analysis.dataflow.interprocedural.AnalysisResults;
import analysis.dataflow.util.AbstractLocation;

import com.ibm.wala.ipa.callgraph.CGNode;

/**
 * Abstract heap locations accessible from a given call graph node and any callees
 */
public class AccessibleLocationResults implements AnalysisResults {

    private final Map<CGNode, AbstractLocationSet> accessibleLocations;

    public AccessibleLocationResults() {
        this.accessibleLocations = new LinkedHashMap<>();
    }

    public void setResults(CGNode n, AbstractLocationSet results) {
        this.accessibleLocations.put(n, results);
    }

    public Set<AbstractLocation> getResults(CGNode n) {
        AbstractLocationSet s = accessibleLocations.get(n);
        assert s != null : "No accessible location results for " + n;
        return s.getRawSet();
    }
}
