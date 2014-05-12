package analysis.pointer.analyses;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;

/**
 * A HeapAbstractionFactory is responsible for providing an appropriate
 * abstraction of the heap for pointer analysis. It is based on the factoring of
 * pointer analysis in the paper "Pick Your Contexts Well: Understanding
 * Object-Sensitivity" by Smaragdakis, Bravenboer, and Lhotak, POPL 2011.
 * 
 */
public interface HeapAbstractionFactory {

    /**
     * Create a new abstract object created at a particular allocation site in a
     * particular code context.
     * 
     * @param context
     *            Code context at the allocation site
     * @param allocationSite
     *            Representation of the program counter for the allocation site
     * @param ir
     *            allocation site code, needed to disambiguate allocation sites
     * 
     * @return Abstract heap object
     */
    InstanceKey record(Context context, AllocSiteNode allocationSite, IR ir);

    /**
     * Create a new code context for a new callee
     * 
     * @param callSite
     *            call site we are creating a node for
     * @param ir
     *            call site code, needed to disambiguate call sites
     * @param receiver
     *            Abstract object representing the receiver
     * @param callerContext
     *            Code context in the method caller
     * @return code context for the callee
     */
    Context merge(CallSiteReference callSite, IR ir, InstanceKey receiver, Context callerContext);

    /**
     * Return the initial Context, i.e., to analyze the root method.
     */
    Context initialContext();
}
