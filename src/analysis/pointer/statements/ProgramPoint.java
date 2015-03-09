package analysis.pointer.statements;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class ProgramPoint {

    private final int id;
    private final PreProgramPoint pre;
    private final PostProgramPoint post;
    private final IMethod containingProcedure;
    private String debugInfo;

    private final boolean isEntrySummaryNode;
    private final boolean isNormalExitSummaryNode;
    private final boolean isExceptionExitSummaryNode;

    /**
     * Is this program point discarded, or is it still relevant.
     */
    private boolean isDiscarded = false;

    private Set<ProgramPoint> succs;

    private static int generator;

    public ProgramPoint(IMethod containingProcedure, String debugInfo) {
        this(containingProcedure, debugInfo, false, false, false);
    }

    @SuppressWarnings("synthetic-access")
    public ProgramPoint(IMethod containingProcedure, String debugInfo,
                        boolean isEntrySummaryNode,
                        boolean isNormalExitSummaryNode, boolean isExceptionExitSummaryNode) {
        this.id = ++generator;
        this.containingProcedure = containingProcedure;
        this.pre = new PreProgramPoint(this);
        this.post = new PostProgramPoint(this);
        this.debugInfo = debugInfo;
        if (containingProcedure == null) {
            throw new IllegalArgumentException("procuedure should be nonnull");
        }
        this.isEntrySummaryNode = isEntrySummaryNode;
        this.isNormalExitSummaryNode = isNormalExitSummaryNode;
        this.isExceptionExitSummaryNode = isExceptionExitSummaryNode;
        this.succs = null;
    }

    public PreProgramPoint pre() {
        return pre;
    }

    public PostProgramPoint post() {
        return post;
    }

    public IMethod containingProcedure() {
        return this.containingProcedure;
    }

    public Set<ProgramPoint> succs() {
        if (this.succs == null) {
            return Collections.emptySet();
        }
        return this.succs;
    }

    public boolean addSucc(ProgramPoint succ) {
        if (this.succs == null) {
            this.succs = AnalysisUtil.createConcurrentSet();
        }
        return this.succs.add(succ);
    }

    public boolean addSuccs(Collection<ProgramPoint> succs) {
        boolean changed = false;
        if (succs != null) {
            for (ProgramPoint pp : succs) {
                changed |= this.addSucc(pp);
            }
        }
        return changed;
    }

    @Override
    public String toString() {
        return "pp" + id + "(" + this.containingProcedure.getSignature() + ": " + debugInfo + ")";
    }

    public String toStringSimple() {
        return "pp" + id;
    }

    public int getID() {
        return id;
    }

    public IMethod getContainingProcedure() {
        return containingProcedure;
    }

    @Override
    public final int hashCode() {
        return id;
    }

    public String getDebugInfo() {
        return debugInfo;
    }

    public boolean isEntrySummaryNode() {
        return isEntrySummaryNode;
    }

    public boolean isNormalExitSummaryNode() {
        return isNormalExitSummaryNode;
    }

    public boolean isExceptionExitSummaryNode() {
        return isExceptionExitSummaryNode;
    }

    public boolean isDiscarded() {
        return this.isDiscarded;
    }

    @Override
    public final boolean equals(Object o) {
        return (this == o);
    }

    public final static class PreProgramPoint implements InterProgramPoint {
        final ProgramPoint pp;

        private PreProgramPoint(ProgramPoint pp) {
            this.pp = pp;
            assert pp != null;
        }

        @Override
        public String toString() {
            // return "**" + pp.getID() + "_pre(" + pp.getDebugInfo() + ")**";
            return "**" + pp.getID() + "_pre** " + pp;
        }

        @Override
        public int hashCode() {
            return pp.hashCode() ^ 9;
        }

        @Override
        public boolean equals(Object obj) {
            // object equality
            return this == obj;
        }

        @Override
        public ProgramPoint getPP() {
            return this.pp;
        }

        @Override
        public InterProgramPointReplica getReplica(Context context) {
            return InterProgramPointReplica.create(context, this);
        }

    }

    public final static class PostProgramPoint implements InterProgramPoint {
        final ProgramPoint pp;

        private PostProgramPoint(ProgramPoint pp) {
            this.pp = pp;
            assert pp != null;
        }

        @Override
        public String toString() {
            //return "**" + pp.getID() + "_post(" + pp.getDebugInfo() + ")**";
            return "**" + pp.getID() + "_post** " + pp;
        }

        @Override
        public final int hashCode() {
            return pp.hashCode() ^ 45;
        }

        @Override
        public final boolean equals(Object obj) {
            // object equality
            return this == obj;
        }

        @Override
        public ProgramPoint getPP() {
            return this.pp;
        }

        @Override
        public InterProgramPointReplica getReplica(Context context) {
            return InterProgramPointReplica.create(context, this);
        }

    }

    public final static class ProgramPointReplica {
        private final ProgramPoint pp;
        private final Context cc;

        public static ProgramPointReplica create(Context context, ProgramPoint pp) {
            // XXX!@! we will eventually do memoization here.
            assert pp != null && context != null;
            return new ProgramPointReplica(context, pp);
        }

        private ProgramPointReplica(Context cc, ProgramPoint pp) {
            this.pp = pp;
            this.cc = cc;
        }

        @Override
        public String toString() {
            return "(" + pp.toString() + ":" + cc.toString() + ")";
        }

        @Override
        public int hashCode() {
            return cc.hashCode() + 31 * pp.hashCode();
        }

        public Context getContext() {
            return cc;
        }

        public ProgramPoint getPP() {
            return pp;
        }

        public InterProgramPointReplica pre() {
            return InterProgramPointReplica.create(this.cc, pp.pre());
        }

        public InterProgramPointReplica post() {
            return InterProgramPointReplica.create(this.cc, pp.post());
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
            ProgramPointReplica other = (ProgramPointReplica) obj;
            if (!pp.equals(other.pp)) {
                return false;
            }
            if (!cc.equals(other.cc)) {
                return false;
            }
            return true;
        }
    }
    public static interface InterProgramPoint {
        public ProgramPoint getPP();

        public InterProgramPointReplica getReplica(Context context);
    }

    public final static class InterProgramPointReplica {
        private InterProgramPoint ipp;
        private Context cc;

        public static InterProgramPointReplica create(Context context,
                InterProgramPoint ipp) {
            // XXX!@! we will eventually do memoization here.
            return new InterProgramPointReplica(context, ipp);
        }

        private InterProgramPointReplica(Context cc, InterProgramPoint ipp) {
            this.ipp = ipp;
            this.cc = cc;
            assert cc != null && ipp != null;
        }

        @Override
        public String toString() {
            return "(" + ipp.toString() + ":" + cc.toString() + ")";
        }

        @Override
        public int hashCode() {
            return cc.hashCode() + 31 * ipp.hashCode();
        }

        public Context getContext() {
            return cc;
        }

        public InterProgramPoint getInterPP() {
            return ipp;
        }

        public IMethod getContainingProcedure() {
            return ipp.getPP().getContainingProcedure();
        }

        /**
         * Get the replica for the program point for which this is either the pre or post program point
         *
         * @return program point replica
         */
        public ProgramPointReplica getRegularProgramPointReplica() {
            return ProgramPointReplica.create(getContext(), ipp.getPP());
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
            InterProgramPointReplica other = (InterProgramPointReplica) obj;
            if (!ipp.equals(other.ipp)) {
                return false;
            }
            if (!cc.equals(other.cc)) {
                return false;
            }
            return true;
        }

    }

    /**
     * Break this program point into two. Imperatively, what happens is that we create a new program point pp (which is
     * returned), which has as its successors the succs of this object, and then this object has its succs cleared.
     *
     * The caller of this method typically adds pp as a succ to this object (or connects this program point to pp
     * transitively via succ relations).
     *
     * @return
     */
    public ProgramPoint divide(String debugStringPre) {
        assert !(this.isEntrySummaryNode || this.isExceptionExitSummaryNode || this.isNormalExitSummaryNode);
        ProgramPoint pp = new ProgramPoint(this.containingProcedure, this.debugInfo);
        return this.divide(debugStringPre, pp);
    }

    public ProgramPoint divide(String debugStringPre, ProgramPoint pp) {
        assert !(this.isEntrySummaryNode || this.isExceptionExitSummaryNode || this.isNormalExitSummaryNode);
        pp.addSuccs(this.succs);
        this.succs.clear();
        if (debugStringPre != null) {
            this.debugInfo = this.debugInfo + debugStringPre;
        }
        return pp;
    }

    public void removeSucc(ProgramPoint pp) {
        assert this.succs.contains(pp);
        this.succs.remove(pp);

    }

    public void clearSuccs() {
        this.succs = null;

    }

    public void setIsDiscardedProgramPoint() {
        if (this.succs != null) {
            this.succs.clear();
        }
        this.isDiscarded = true;
    }

    public ProgramPointReplica getReplica(Context context) {
        return ProgramPointReplica.create(context, this);
    }
}
