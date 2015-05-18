package analysis.pointer.analyses;

import java.util.ArrayList;
import java.util.List;

import util.optional.Optional;

import com.ibm.wala.classLoader.IClass;

public class SOPProduct implements StringOrProperty {
    public final List<StringOrPropertyFlat> elements;

    private static final SOPProduct EMPTY = new SOPProduct(new ArrayList<StringOrPropertyFlat>());

    /*
     * Factory Methods
     */

    public static SOPProduct makeEmpty() {
        return EMPTY;
    }

    public static StringOrProperty makePair(StringOrPropertyFlat x, StringOrPropertyFlat y) {
        List<StringOrPropertyFlat> l = new ArrayList<>();
        l.add(x);
        l.add(y);
        return new SOPProduct(l);
    }

    /*
     * Constructors
     */

    private SOPProduct(List<StringOrPropertyFlat> elements) {
        this.elements = elements;
    }

    /*
     * Logic
     */

    @Override
    public Optional<IClass> toIClass() {
        if (elements.size() == 1) {
            return elements.get(0).toIClass();
        }
        else {
            return Optional.none();
        }
    }

    @Override
    public StringOrProperty concat(StringOrProperty that) {
        return that.revconcatVisit(this);
    }

    @Override
    public StringOrProperty revconcatVisit(SOPProperty sop) {
        List<StringOrPropertyFlat> elements2 = new ArrayList<>(this.elements);
        elements2.add(0, sop);
        return new SOPProduct(elements2);
    }

    @Override
    public StringOrProperty revconcatVisit(SOPString sop) {
        List<StringOrPropertyFlat> elements2 = new ArrayList<>(this.elements);
        elements2.add(0, sop);
        return new SOPProduct(elements2);
    }

    @Override
    public StringOrProperty revconcatVisit(SOPProduct sop) {
        if (this.elements.size() == 0) {
            return sop;
        }
        else if (this.elements.size() == 1) {
            return this.elements.get(0).revconcatVisit(sop);
        }
        else {
            StringOrPropertyFlat last = sop.elements.get(sop.elements.size() - 1);
            StringOrProperty lastAndThis = last.concat(this);

            if (lastAndThis instanceof SOPProduct) {
                SOPProduct lastAndThisSOPP = ((SOPProduct) lastAndThis);
                List<StringOrPropertyFlat> elements2 = new ArrayList<>(sop.elements.size()
                        + lastAndThisSOPP.elements.size());
                elements2.addAll(sop.elements);
                elements2.remove(elements2.size() - 1);
                elements2.addAll(lastAndThisSOPP.elements);
                return new SOPProduct(elements2);
            }
            else {
                List<StringOrPropertyFlat> elements2 = new ArrayList<>(this.elements.size());
                elements2.addAll(this.elements);
                // safe because SOPProduct and StringOrPropertyFlat are disjoint subsets
                // and their union is equal to the whole StringOrProperty type
                elements2.set(elements2.size() - 1, (StringOrPropertyFlat) lastAndThis);
                return new SOPProduct(elements2);
            }
        }
    }

    public StringOrProperty putOnFront(SOPProperty sop) {
        List<StringOrPropertyFlat> elements2 = new ArrayList<>(this.elements.size());
        elements2.addAll(this.elements);
        elements2.add(0, sop);
        return new SOPProduct(elements2);
    }

    public StringOrProperty putOnFront(SOPString sop) {
        List<StringOrPropertyFlat> elements2 = new ArrayList<>(this.elements.size());
        elements2.addAll(this.elements);
        if (elements2.get(0) instanceof SOPString) {
            elements2.set(0, sop.stringConcat((SOPString) elements2.get(0)));
        }
        else {
            elements2.add(0, sop);
        }
        return new SOPProduct(elements2);
    }

    @Override
    public String toString() {
        return "SOPProduct [elements=" + elements + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((elements == null) ? 0 : elements.hashCode());
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
        if (!(obj instanceof SOPProduct)) {
            return false;
        }
        SOPProduct other = (SOPProduct) obj;
        if (elements == null) {
            if (other.elements != null) {
                return false;
            }
        }
        else if (!elements.equals(other.elements)) {
            return false;
        }
        return true;
    }

}
