package analysis.pointer.analyses;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import util.FiniteSet;

import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.functions.Function;

public final class AString {
    private FiniteSet<StringOrProperty> fs;

    public static AString makeStringTop(int maxSize) {
        return new AString(FiniteSet.<StringOrProperty> getTop());
    }

    public static AString makeStringBottom(int maxSize) {
        return new AString(FiniteSet.<StringOrProperty> makeBottom(maxSize));
    }

    public static AString makeProperty(int maxSize, StringOrProperty property) {
        Collection<StringOrProperty> c = new HashSet<>();
        c.add(property);
        return makePropertySet(maxSize, c);
    }

    public static AString makeString(int maxSize, String property) {
        Collection<String> c = new HashSet<>();
        c.add(property);
        return makeStringSet(maxSize, c);
    }

    public static AString makeStringSet(int maxSize, Collection<String> c) {
        return new AString(FiniteSet.makeFiniteSet(maxSize, mapStringInject(c)));
    }

    public static AString makeFromFiniteSet(FiniteSet<String> strings) {
        return new AString(strings.map(new Function<String, StringOrProperty>() {
            @Override
            public StringOrProperty apply(String s) {
                return SOPString.make(s);
            }
        }));
    }

    public static AString makePropertySet(int maxSize, Collection<StringOrProperty> c) {
        return new AString(FiniteSet.makeFiniteSet(maxSize, mapPropertyInject(c)));
    }

    private static Collection<StringOrProperty> mapPropertyInject(Collection<StringOrProperty> c) {
        Collection<StringOrProperty> c2 = new HashSet<>();
        for (StringOrProperty s : c) {
            c2.add(SOPProperty.make(s));
        }
        return c2;
    }

    private static Collection<StringOrProperty> mapStringInject(Collection<String> c) {
        Collection<StringOrProperty> c2 = new HashSet<>();
        for (String s : c) {
            c2.add(SOPString.make(s));
        }
        return c2;
    }

    public static AString makeStringOrPropertySet(int maxSize, Collection<StringOrProperty> c) {
        return new AString(FiniteSet.makeFiniteSet(maxSize, c));
    }

    private AString(FiniteSet<StringOrProperty> fs) {
        this.fs = fs;
    }

    /**
     *
     * @param sik
     * @return true if the join resulted in a new AString
     */
    public AString join(AString sik) {
        return new AString(this.fs.union(sik.fs));
    }

    /**
     * Throws an exception if this set is top
     *
     * @return the set of strings represented by this AString
     */
    public Set<StringOrProperty> getStrings() {
        return this.fs.getSet();
    }

    public FiniteSet<StringOrProperty> getFiniteStringSet() {
        return this.fs;
    }

    public Iterator<Pair<CGNode, NewSiteReference>> getCreationSites(CallGraph CG) {
        throw new UnsupportedOperationException();
    }

    public boolean isTop() {
        return this.fs.isTop();
    }

    public boolean isBottom() {
        return this.fs.isBottom();
    }

    public boolean upperBounds(AString that) {
        return this.fs.containsAll(that.fs);
    }

    public AString concat(final AString that) {
        // str1 -> that.fs.map(str2 -> str1.concat(str2))
        final Function<StringOrProperty, FiniteSet<StringOrProperty>> fOuter = new Function<StringOrProperty, FiniteSet<StringOrProperty>>() {
            @Override
            public FiniteSet<StringOrProperty> apply(final StringOrProperty str1) {
                final Function<StringOrProperty, StringOrProperty> fInner = new Function<StringOrProperty, StringOrProperty>() {
                    @Override
                    public StringOrProperty apply(StringOrProperty str2) {
                        return str1.concat(str2);
                    }
                };
                return that.fs.map(fInner);
            }
        };

        //        System.err.print("Just concated, before: " + this.fs);
        FiniteSet<StringOrProperty> s = this.fs.flatMap(fOuter);
        //        System.err.println(", argument: " + that.fs + ", after: " + s);
        return new AString(s);
    }

    @Override
    public String toString() {
        return "SIK(" + this.fs.toString() + ")";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((fs == null) ? 0 : fs.hashCode());
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
        if (!(obj instanceof AString)) {
            return false;
        }
        AString other = (AString) obj;
        if (fs == null) {
            if (other.fs != null) {
                return false;
            }
        }
        else if (!fs.equals(other.fs)) {
            return false;
        }
        return true;
    }

}
