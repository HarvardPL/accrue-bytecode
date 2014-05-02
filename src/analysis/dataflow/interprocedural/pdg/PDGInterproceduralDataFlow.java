package analysis.dataflow.interprocedural.pdg;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import util.WorkQueue;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.dataflow.interprocedural.pdg.graph.ProgramDependenceGraph;
import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.dataflow.util.AbstractLocation;
import analysis.dataflow.util.Unit;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;

public class PDGInterproceduralDataFlow extends InterproceduralDataFlow<Unit> {

    private final ProgramDependenceGraph pdg;
    private final PreciseExceptionResults preciseEx;

    public PDGInterproceduralDataFlow(PointsToGraph ptg, PreciseExceptionResults preciseEx,
                                    ReachabilityResults reachable) {
        super(ptg, reachable);
        this.pdg = new ProgramDependenceGraph();
        this.preciseEx = preciseEx;
        // TODO Auto-generated constructor stub
    }

    @Override
    protected String getAnalysisName() {
        return "PDG data-flow";
    }

    @Override
    protected Map<ExitType, Unit> analyze(CGNode n, Unit input) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ExitType, Unit> analyzeNative(CGNode n, Unit input) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ExitType, Unit> getDefaultOutput(Unit input) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Unit getInputForEntryPoint() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected boolean outputChanged(Map<ExitType, Unit> previousOutput,
                                    Map<ExitType, Unit> currentOutput) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    protected boolean existingResultSuitable(
                                    Unit newInput,
                                    AnalysisRecord<Unit> existingResults) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    protected void preAnalysis(CallGraph cg, WorkQueue<CGNode> q) {
        // Add all call graph nodes reachable from an entry point
        Collection<CGNode> entryPoints = cg.getEntrypointNodes();

        // These are the class initializers
        q.addAll(entryPoints);
        // Also add the fake root method (which calls main)
        q.add(cg.getFakeRootNode());

        // Queue used to initialize the data-flow work-queue
        WorkQueue<CGNode> initializationQ = new WorkQueue<>();
        
        initializationQ.addAll(q);
        while (!initializationQ.isEmpty()) {
            CGNode n = initializationQ.poll();
            Iterator<CGNode> iter = cg.getSuccNodes(n);
            while (iter.hasNext()) {
                CGNode succ = iter.next();
                if (!q.contains(succ)) {
                    // TODO only add reachable calls
                    // most should be reachable
                    
                    // Add to the data-flow work-queue
                    q.add(succ);
                    
                    // Set up initial context
                    AnalysisRecord<Unit> res = new AnalysisRecord<Unit>(getInitialContext(succ), null, true);
                    recordedResults.put(succ, res);
                    
                    // Add to the inialization work-queue
                    initializationQ.add(succ);
                }
            }
        }

        super.preAnalysis(cg, q);
    }

    private Unit getInitialContext(CGNode succ) {
        // TODO Auto-generated method stub

        // PC entry node
        // PC normal exit
        // PC exception exit
        // Formal argument nodes
        // Return node if not void
        // Exception node if it can throw one

        // Memoize after creation
        return null;
    }

    @Override
    protected void postAnalysis() {
        // TODO Auto-generated method stub

        // Construct the inter-procedural PDG using the intra-procedural summary
        // nodes and the call-graph.

        // Be careful not to add any unreachable calls (this is a precision
        // issue not a soundness issue).
        super.postAnalysis();
    }
    
    public PreciseExceptionResults getPreciseExceptionResults() {
        return preciseEx;
    }

    public PDGNode getAbstractLocationNode(AbstractLocation loc) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Map<ExitType, Unit> getResults(CGNode caller, CGNode callee, Unit input) {
        throw new UnsupportedOperationException("Get the summary and use that instead.");
    }
    
    @Override
    public ProgramDependenceGraph getAnalysisResults() {
        return pdg;
    }
}
