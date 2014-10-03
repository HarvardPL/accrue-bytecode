package analysis.pointer.statements;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class ProgramPoint {

    private final int id;
    private final PreProgramPoint pre;
    private final PostProgramPoint post;
    private final IMethod containingProcedure;
    private final String debugInfo;

    private final boolean isEntrySummaryNode;
    private final boolean isNormalExitSummaryNode;
    private final boolean isExceptionExitSummaryNode;

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

    @Override
    public String toString() {
        return "pp" + id + "(" + debugInfo + ")";
    }

    public int getID() {
        return id;
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
            return "**" + pp.getID() + "_pre(" + pp.getDebugInfo() + ")**";
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

    }

    public static class PostProgramPoint implements InterProgramPoint {
        ProgramPoint pp;

        public PostProgramPoint(ProgramPoint pp) {
            this.pp = pp;
        }

        @Override
        public String toString() {
            return "**" + pp.getID() + "_post(" + pp.getDebugInfo() + ")**";
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
}
