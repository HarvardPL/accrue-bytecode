package util;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import util.optional.Optional;

import com.ibm.wala.util.functions.Function;

/**
 * This represents a set of a bounded size, and, if the size goes above that bound, it becomes a representation of the
 * entire domain.
 *
 * Objects are immutable.
 *
 * @param <T>
 */
public final class FiniteSet<T> {
    /**
     * The maximum size of the set. -1 if this is the object representing the top set.
     */
    private final int maxSize;

    /**
     * The items in the set. Is null if and only if this is the object representing the top set. Otherwise, the size is
     * at most this.maxSize.
     */
    private final Set<T> items;

    public static final FiniteSet<?> TOP = new FiniteSet<>(-1, null);

    /* factories */
    public static <T> FiniteSet<T> getTop() {
        return (FiniteSet<T>) TOP;
    }

    public static <T> FiniteSet<T> makeBottom(int maxSize) {
        return new FiniteSet<T>(maxSize, Collections.EMPTY_SET); // it's fine to use a hashset, since this is immutable.
    }

    public static <T> FiniteSet<T> makeFiniteSet(int maxSize, Collection<T> items) {
        if (items.size() > maxSize) {
            return getTop();
        }
        else {
            return new FiniteSet<>(maxSize, new HashSet<>(items));
        }
    }

    /*
     * Get rid of this method once Optional is gone...
     */
    public static <T> FiniteSet<T> make(int maxSize, Optional<? extends Collection<T>> c) {
        if (c.isNone()) {
            return getTop();
        }
        return makeFiniteSet(maxSize, c.get());
    }

    /* constructors */
    private FiniteSet(int maxSize, Set<T> items) {
        assert (maxSize < 0 && items == null) || (items != null && items.size() <= maxSize) : "maxSize is " + maxSize
                + " and items is " + items;
        this.maxSize = maxSize;
        this.items = items;
    }

    /* set operations */

    /**
     * Returns a finite set representing the union this this and that.
     *
     **/
    public FiniteSet<T> union(FiniteSet<T> that) {
        if (this.isTop() || that.isTop()) {
            return getTop();
        }

        if (this.maxSize != that.maxSize) {
            throw new IllegalArgumentException("Cannot union finite sets of different sizes. `this` has size "
                    + this.maxSize + " while the other finite set has size " + that.maxSize);
        }

        if (this.isBottom()) {
            return that;
        }
        if (that.isBottom()) {
            return this;
        }

        HashSet<T> union = new HashSet<>(this.items.size() + that.items.size());
        union.addAll(this.items);
        union.addAll(that.items);
        if (union.size() > this.maxSize) {
            return getTop();
        }
        return new FiniteSet<>(this.maxSize, union);
    }

    public boolean isTop() {
        return this == FiniteSet.TOP;
    }

    public boolean isBottom() {
        return this.items != null && this.items.isEmpty();
    }

    /**
     * Does this contain all of that?
     */
    public boolean containsAll(FiniteSet<T> that) {
        if (this.isTop() || that.isBottom()) {
            return true;
        }
        else if (that.isTop() || this.isBottom()) {
            return false;
        }
        return this.items.containsAll(that.items);

    }

    public Set<T> getSet() {
        if (this.isTop()) {
            throw new RuntimeException("Cannot get set of Top");
        }
        else {
            return this.items;
        }
    }

    public int getMaxSize() {
        return this.maxSize;
    }

    public <U> FiniteSet<U> map(Function<? super T, U> f) {
        if (this.isTop()) {
            return getTop();
        }
        HashSet<U> result = new HashSet<U>();

        for (T item : this.items) {
            result.add(f.apply(item));
        }
        return new FiniteSet<>(this.maxSize, result);
    }

    public <U> FiniteSet<U> flatMap(Function<? super T, ? extends FiniteSet<U>> f) {
        if (this.isTop()) {
            return getTop();
        }
        HashSet<U> result = new HashSet<U>();
        for (T t : this.items) {
            FiniteSet<U> r = f.apply(t);
            if (r.isTop()) {
                return getTop();
            }
            result.addAll(r.getSet());
            if (result.size() > this.maxSize) {
                return getTop();
            }
        }
        return new FiniteSet<>(this.maxSize, result);
    }

    @Override
    public String toString() {
        if (this.isTop()) {
            return "FS(⊤)";
        }
        else if (this.isBottom()) {
            return "FS(⊥)";
        }
        else {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            Iterator<T> it = this.items.iterator();
            while (it.hasNext()) {
                sb.append(stringify(it.next()));
                if (it.hasNext()) {
                    sb.append(", ");
                }
            }
            sb.append("}");
            return "FS(" + sb.toString() + ")";
        }
    }

    private static String stringify(Object o) {
        if (o instanceof String) {
            if (o.equals("")) {
                return "ε";
            }
            else {
                return "\"" + o + "\"";
            }
        }
        else {
            return o.toString();
        }
    }

}
