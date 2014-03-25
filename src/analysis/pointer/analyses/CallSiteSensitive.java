package analysis.pointer.analyses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import util.PrettyPrinter;
import analysis.pointer.graph.AllocSiteNode;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.util.collections.Pair;

/**
 * Analysis where the contexts are based on procedure call sites
 */
public class CallSiteSensitive implements HeapAbstractionFactory {

    /**
     * Default depth of call sites to keep track of
     */
    private static final int DEFAULT_SENSITIVITY = 2;
    /**
     * Depth of the call sites to keep track of
     */
    private final int sensitivity;
    /**
     * Empty context with no call sites
     */
    private static final Context EMPTY_CONTEXT = new ContextStack(Collections.<CallSiteReference> emptyList(),
            Collections.<IR> emptyList());

    /**
     * Create a call site sensitive heap abstraction factory with the default
     * depth
     */
    public CallSiteSensitive() {
        this(DEFAULT_SENSITIVITY);
    }

    /**
     * Create a call site sensitive heap abstraction factory with the given
     * depth
     * 
     * @param sensitivity
     *            depth of the call site stack
     */
    public CallSiteSensitive(int sensitivity) {
        this.sensitivity = sensitivity;
    }

    @Override
    public String toString() {
        return "Context(" + this.sensitivity + ")";
    }

    @Override
    public InstanceKey record(Context context, AllocSiteNode allocationSite, IR ir) {
        return new AllocationName((ContextStack) context, allocationSite, ir);
    }

    @Override
    public Context merge(CallSiteReference callSite, IR ir, InstanceKey receiver, Context callerContext) {
        ContextStack caller = (ContextStack) callerContext;
        return caller.pushCallSite(callSite, ir, sensitivity);
    }

    @Override
    public Context initialContext() {
        return EMPTY_CONTEXT;
    }

    /**
     * A sequence of call sites
     */
    private static class ContextStack implements Context {

        public static final ContextKey CALL_SITE_STACK_STRING = new ContextKey() {
            @Override
            public String toString() {
              return "CALL_SITE_STACK_STRING_KEY";
            }
          };
          public static final ContextKey CALL_SITE_IR_STACK_STRING = new ContextKey() {
              @Override
              public String toString() {
                return "CALL_SITE_IR_STACK_STRING_KEY";
              }
            };

        /**
         * List of call sites in the stack
         */
        private final List<CallSiteReference> sites;
        /**
         * List of code for call sites in the stack (needed to disambiguate)
         */
        private final List<IR> irs;

        /**
         * Create a new stack from the given list of call sites
         * 
         * @param sites
         *            call sites in the new stack
         * @param irs
         *            Code for call sites in new stack
         */
        private ContextStack(List<CallSiteReference> sites, List<IR> irs) {
            assert (sites.size() == irs.size());
            this.sites = sites;
            this.irs = irs;
        }

        /**
         * Add the given call site to the stack
         * 
         * @param csn
         *            call site to add
         * @param sensitivity
         *            max depth of the call stack
         * @return new context stack with the given call site added
         */
        public ContextStack pushCallSite(CallSiteReference csn, IR ir, int sensitivity) {
            ArrayList<CallSiteReference> s1 = new ArrayList<CallSiteReference>(sites.size() + 1);
            ArrayList<IR> s2 = new ArrayList<>(sites.size() + 1);
            s1.add(csn);
            s1.addAll(sites);

            s2.add(ir);
            s2.addAll(irs);
            while (s1.size() > sensitivity) {
                s1.remove(s1.size() - 1);
                s2.remove(s2.size() - 1);
            }
            return new ContextStack(s1, s2);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((sites == null) ? 0 : sites.hashCode());
            result = prime * result + ((irs == null) ? 0 : irs.hashCode());
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
            ContextStack other = (ContextStack) obj;
            if (sites == null) {
                if (other.sites != null)
                    return false;
            } else if (!sites.equals(other.sites))
                return false;
            if (irs == null) {
                if (other.irs != null)
                    return false;
            } else if (!irs.equals(other.irs))
                return false;
            return true;
        }

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder();
            if (!sites.isEmpty()) {
                s.append(PrettyPrinter.parseMethod(sites.get(0).getDeclaredTarget()) + " ");
            }
            s.append("[");
            int len = sites.size() - 1;
            for (int i = 0; i <= len; i++) {
                CallSiteReference site = sites.get(i);
                IR callSiteCode = irs.get(i);
                String meth = PrettyPrinter.parseMethod(callSiteCode.getMethod().getReference()) + "@" + site.getProgramCounter();
                String sep = (i == len) ? "" : ", ";
                s.append(meth + sep);
            }
            s.append("]");
            return s.toString();
        }

        @Override
        public ContextItem get(ContextKey name) {
            if (CALL_SITE_STACK_STRING.equals(name)) {
                return new ContextItem.Value<List<CallSiteReference>>(this.sites);
            }
            if (CALL_SITE_IR_STACK_STRING.equals(name)) {
                return new ContextItem.Value<List<IR>>(this.irs);
            }
            return null;
        }
    }

    /**
     * Heap context that is simply the allocation site
     */
    private static class AllocationName implements InstanceKey {
        /**
         * Allocation site
         */
        private final AllocSiteNode asn;
        /**
         * Code for the allocation site needed to disambiguate
         */
        private final IR ir;
        /**
         * Caller context for the allocation
         */
        private final ContextStack context;
        /**
         * pre-computed hash code
         */
        private final int hashCode;

        /**
         * New allocation site
         * 
         * @param contextStack
         *            context in which the allocation occurs
         * @param asn
         *            allocation site
         * @param ir
         *            Code for the allocation site
         */
        public AllocationName(ContextStack contextStack, AllocSiteNode asn, IR ir) {
            this.context = contextStack;
            this.asn = asn;
            this.ir = ir;
            this.hashCode = this.computeHashCode();
        }

        /**
         * Compute the hash code once
         * 
         * @return hash code
         */
        private int computeHashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((asn == null) ? 0 : asn.hashCode());
            result = prime * result + ((context == null) ? 0 : context.hashCode());
            result = prime * result + ((ir == null) ? 0 : ir.hashCode());
            return result;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            AllocationName other = (AllocationName) obj;
            if (asn == null) {
                if (other.asn != null)
                    return false;
            } else if (!asn.equals(other.asn))
                return false;
            if (context == null) {
                if (other.context != null)
                    return false;
            } else if (!context.equals(other.context))
                return false;
            if (ir == null) {
                if (other.ir != null)
                    return false;
            } else if (!ir.equals(other.ir))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return asn + " in " + context;
        }

        @Override
        public IClass getConcreteType() {
           return asn.getInstantiatedClass();
        }

        @Override
        public Iterator<Pair<CGNode, NewSiteReference>> getCreationSites(CallGraph CG) {
            throw new UnsupportedOperationException();
        }
    }
}
