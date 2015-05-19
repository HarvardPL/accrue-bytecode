package analysis.pointer.analyses;

import util.optional.Optional;
import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

public class SOPString implements StringOrPropertyFlat {
    private final String s;

    public static SOPString make(String s) {
        return new SOPString(s);
    }

    private SOPString(String s) {
        this.s = s;
    }

    @Override
    public Optional<IClass> toIClass() {
        IClass result = AnalysisUtil.getClassHierarchy()
                                    .lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, "L"
                                            + this.s.replace(".", "/")));
        if (result == null) {
            System.err.println("[SOPString] Could not find class for: " + this.s);
            return Optional.none();
        }
        else {
            return Optional.some(result);
        }
    }

    @Override
    public StringOrProperty concat(StringOrProperty sop) {
        return sop.revconcatVisit(this);
    }

    @Override
    public StringOrProperty revconcatVisit(SOPProperty sop) {
        return SOPProduct.makePair(this, sop);
    }

    @Override
    public StringOrProperty revconcatVisit(SOPString sop) {
        return sop.stringConcat(this);
    }

    @Override
    public StringOrProperty revconcatVisit(SOPProduct sop) {
        return sop.putOnFront(this);
    }

    public StringOrPropertyFlat stringConcat(SOPString sop) {
        return new SOPString(s.concat(sop.s));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((s == null) ? 0 : s.hashCode());
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
        if (!(obj instanceof SOPString)) {
            return false;
        }
        SOPString other = (SOPString) obj;
        if (s == null) {
            if (other.s != null) {
                return false;
            }
        }
        else if (!s.equals(other.s)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "SOPString [s=" + s + "]";
    }
}
