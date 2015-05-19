package analysis.pointer.analyses;

import util.optional.Optional;

import com.ibm.wala.classLoader.IClass;

public class SOPProperty implements StringOrPropertyFlat {
    private final StringOrProperty name;

    public static SOPProperty make(StringOrProperty name) {
        return new SOPProperty(name);
    }

    private SOPProperty(StringOrProperty name) {
        this.name = name;
    }

    @Override
    public Optional<IClass> toIClass() {
        System.err.println("[SOPProperty] Could not find class for a PROPERTY: " + this.name);
        return Optional.none();
    }

    @Override
    public StringOrProperty concat(StringOrProperty that) {
        return that.revconcatVisit(this);
    }

    @Override
    public StringOrProperty revconcatVisit(SOPProperty sop) {
        return SOPProduct.makePair(sop, this);
    }

    @Override
    public StringOrProperty revconcatVisit(SOPString sop) {
        return SOPProduct.makePair(sop, this);
    }

    @Override
    public StringOrProperty revconcatVisit(SOPProduct sop) {
        return sop.putOnFront(this);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
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
        if (!(obj instanceof SOPProperty)) {
            return false;
        }
        SOPProperty other = (SOPProperty) obj;
        if (name == null) {
            if (other.name != null) {
                return false;
            }
        }
        else if (!name.equals(other.name)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "SOPProperty [name=" + name + "]";
    }

}
