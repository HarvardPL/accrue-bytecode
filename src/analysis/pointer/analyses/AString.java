package analysis.pointer.analyses;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

import util.FiniteSet;
import util.optional.Optional;
import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.functions.Function;

public final class AString {
    private static final IClass JavaLangStringIClass = AnalysisUtil.getClassHierarchy()
                                                                   .lookupClass(TypeReference.JavaLangString);
    private FiniteSet<String> fs;
    private IClass klass;

    public static AString makeStringTop(int maxSize) {
        return new AString(JavaLangStringIClass, FiniteSet.<String> makeTop(maxSize));
    }

    public static AString makeStringBottom(int maxSize) {
        return new AString(JavaLangStringIClass, FiniteSet.<String> makeBottom(maxSize));
    }

    public static AString makeStringSet(int maxSize, Collection<String> c) {
        return new AString(JavaLangStringIClass, FiniteSet.makeFiniteSet(maxSize, c));
    }

    public static AString makeString(int maxSize, Optional<? extends Collection<String>> c) {
        return new AString(JavaLangStringIClass, FiniteSet.make(maxSize, c));
    }

    private AString(IClass c, FiniteSet<String> fs) {
        this.klass = c;
        this.fs = fs;
    }

    /**
     *
     * @param sik
     * @return true if the join resulted in a new AString
     */
    public boolean join(AString sik) {
        return this.fs.union(sik.fs);
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

    public IClass getConcreteType() {
        return this.klass;
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

    public AString copy() {
        return new AString(JavaLangStringIClass, this.fs.copy());
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
        return new AString(JavaLangStringIClass, s);
    }

    @Override
    public String toString() {
        return "SIK(" + this.fs.toString() + ")";
    }
}
