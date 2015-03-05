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

public final class StringInstanceKey {
    private static final IClass JavaLangStringIClass = AnalysisUtil.getClassHierarchy()
                                                                   .lookupClass(TypeReference.JavaLangString);
    private FiniteSet<String> fs;
    private IClass klass;

    public static StringInstanceKey makeStringTop(int maxSize) {
        return new StringInstanceKey(JavaLangStringIClass, FiniteSet.<String> makeTop(maxSize));
    }

    public static StringInstanceKey makeStringBottom(int maxSize) {
        return new StringInstanceKey(JavaLangStringIClass, FiniteSet.<String> makeBottom(maxSize));
    }

    public static StringInstanceKey makeStringSet(int maxSize, Collection<String> c) {
        return new StringInstanceKey(JavaLangStringIClass, FiniteSet.makeFiniteSet(maxSize, c));
    }

    public static StringInstanceKey makeString(int maxSize, Optional<? extends Collection<String>> c) {
        return new StringInstanceKey(JavaLangStringIClass, FiniteSet.make(maxSize, c));
    }

    private StringInstanceKey(IClass c, FiniteSet<String> fs) {
        this.klass = c;
        this.fs = fs;
    }

    public boolean join(StringInstanceKey sik) {
        return this.fs.union(sik.fs);
    }

    public Optional<Set<String>> getStrings() {
        return this.fs.maybeIterable();
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

    public StringInstanceKey concat(final StringInstanceKey that) {
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

        System.err.print("Just concated, before: " + this.fs);
        FiniteSet<String> s = this.fs.flatMap(fOuter);
        System.err.println(", argument: " + that.fs + ", after: " + s);
        return new StringInstanceKey(JavaLangStringIClass, s);
    }

    @Override
    public String toString() {
        return "SIK(" + this.fs.toString() + ")";
    }
}
