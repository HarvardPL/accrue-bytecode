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
        return "pp" + id + "(" + debugInfo + ")";
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
    public int hashCode() {
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
    public boolean equals(Object o) {
        return (this == o);
    }

    public static class PreProgramPoint implements InterProgramPoint {
        ProgramPoint pp;

        public PreProgramPoint(ProgramPoint pp) {
            this.pp = pp;
        }

        @Override
        public String toString() {
            // return "**" + pp.getID() + "_pre(" + pp.getDebugInfo() + ")**";
            return "**" + pp.getID() + "_pre**";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((pp == null) ? 0 : pp.hashCode());
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
            if (!(obj instanceof PreProgramPoint)) {
                return false;
            }
            PreProgramPoint other = (PreProgramPoint) obj;
            if (pp == null) {
                if (other.pp != null) {
                    return false;
                }
            }
            else if (!pp.equals(other.pp)) {
                return false;
            }
            return true;
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

    public static class PostProgramPoint implements InterProgramPoint {
        ProgramPoint pp;

        public PostProgramPoint(ProgramPoint pp) {
            this.pp = pp;
        }

        @Override
        public String toString() {
            //return "**" + pp.getID() + "_post(" + pp.getDebugInfo() + ")**";
            return "**" + pp.getID() + "_post**";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((pp == null) ? 0 : pp.hashCode());
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
            if (!(obj instanceof PostProgramPoint)) {
                return false;
            }
            PostProgramPoint other = (PostProgramPoint) obj;
            if (pp == null) {
                if (other.pp != null) {
                    return false;
                }
            }
            else if (!pp.equals(other.pp)) {
                return false;
            }
            return true;
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

    public static class ProgramPointReplica {
        private ProgramPoint pp;
        private Context cc;

        public static ProgramPointReplica create(Context context, ProgramPoint pp) {
            // XXX!@! we will eventually do memoization here.
            if (pp == null) {
                throw new IllegalArgumentException("Null pp");
            }
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
            final int prime = 31;
            int result = 1;
            result = prime * result + ((cc == null) ? 0 : cc.hashCode());
            result = prime * result + ((pp == null) ? 0 : pp.hashCode());
            return result;
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
            if (cc == null) {
                if (other.cc != null) {
                    return false;
                }
            }
            else if (!cc.equals(other.cc)) {
                return false;
            }
            if (pp == null) {
                if (other.pp != null) {
                    return false;
                }
            }
            else if (!pp.equals(other.pp)) {
                return false;
            }
            return true;
        }
    }
    public static interface InterProgramPoint {
        public ProgramPoint getPP();

        public InterProgramPointReplica getReplica(Context context);
    }

    public static class InterProgramPointReplica {
        private InterProgramPoint ipp;
        private Context cc;

        public static InterProgramPointReplica create(Context context,
                InterProgramPoint ipp) {
            // XXX!@! we will eventually do memoization here.
            if (ipp == null) {
                throw new IllegalArgumentException("Null pp");
            }
            return new InterProgramPointReplica(context, ipp);
        }

        private InterProgramPointReplica(Context cc, InterProgramPoint ipp) {
            this.ipp = ipp;
            this.cc = cc;
        }

        @Override
        public String toString() {
            return "(" + ipp.toString() + ":" + cc.toString() + ")";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((cc == null) ? 0 : cc.hashCode());
            result = prime * result + ((ipp == null) ? 0 : ipp.hashCode());
            return result;
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
            if (cc == null) {
                if (other.cc != null) {
                    return false;
                }
            }
            else if (!cc.equals(other.cc)) {
                return false;
            }
            if (ipp == null) {
                if (other.ipp != null) {
                    return false;
                }
            }
            else if (!ipp.equals(other.ipp)) {
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
        this.succs.clear();
        this.isDiscarded = true;
    }

    public ProgramPointReplica getReplica(Context context) {
        return ProgramPointReplica.create(context, this);
    }
}
