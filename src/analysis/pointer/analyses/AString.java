package analysis.pointer.analyses;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

import util.FiniteSet;
import util.optional.Optional;

import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.functions.Function;

public final class AString {
    private FiniteSet<String> fs;

    public static AString makeStringTop(int maxSize) {
        return new AString(FiniteSet.<String> getTop());
    }

    public static AString makeStringBottom(int maxSize) {
        return new AString(FiniteSet.<String> makeBottom(maxSize));
    }

    public static AString makeStringSet(int maxSize, Collection<String> c) {
        return new AString(FiniteSet.makeFiniteSet(maxSize, c));
    }

    public static AString makeString(int maxSize, Optional<? extends Collection<String>> c) {
        return new AString(FiniteSet.make(maxSize, c));
    }

    private AString(FiniteSet<String> fs) {
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
    public Set<String> getStrings() {
        return this.fs.getSet();
    }

    public FiniteSet<String> getFiniteStringSet() {
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
        final Function<String, FiniteSet<String>> fOuter = new Function<String, FiniteSet<String>>() {
            @Override
            public FiniteSet<String> apply(final String str1) {
                final Function<String, String> fInner = new Function<String, String>() {
                    @Override
                    public String apply(String str2) {
                        return str1.concat(str2);
                    }
                };
                return that.fs.map(fInner);
            }
        };

        //        System.err.print("Just concated, before: " + this.fs);
        FiniteSet<String> s = this.fs.flatMap(fOuter);
        //        System.err.println(", argument: " + that.fs + ", after: " + s);
        return new AString(s);
    }

    @Override
    public String toString() {
        return "SIK(" + this.fs.toString() + ")";
    }

}
