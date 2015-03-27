package util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import util.optional.Optional;

import com.ibm.wala.util.functions.Function;

public final class FiniteSet<T> {
    private final Optional<Set<T>> top = Optional.none();

    private final int maxSize;
    private Optional<Set<T>> items;

    /* factories */

    public static <T> FiniteSet<T> makeBottom(int maxSize) {
        return new FiniteSet<>(maxSize, Optional.some((Set<T>) new HashSet<T>()));
    }

    public static <T> FiniteSet<T> makeTop(int maxSize) {
        return new FiniteSet<>(maxSize, Optional.<Set<T>> none());
    }

    public static <T> FiniteSet<T> makeFiniteSet(int maxSize, Collection<T> items) {
        return new FiniteSet<>(maxSize, Optional.some((Set<T>) new HashSet<>(items)));
    }

    public static <T> FiniteSet<T> make(int maxSize, Optional<? extends Collection<T>> c) {
        return new FiniteSet<>(maxSize, c.isSome() ? Optional.<Set<T>> some(new HashSet<>(c.get()))
                : Optional.<Set<T>> none());
    }

    /* constructors */

    private FiniteSet(int maxSize, Optional<Set<T>> items) {
        this.maxSize = maxSize;
        this.items = items;
    }

    /* set operations */

    /**
     * mutates @code{this} to also contain every element of @code{that}
     *
     * @return true if set changes as a result of this call
     **/
    public boolean union(FiniteSet<T> that) {
        if (this.maxSize != that.maxSize) {
            throw new IllegalArgumentException("Cannot union finite sets of different sizes. `this` has size "
                    + this.maxSize + " while the other finite set has size " + that.maxSize);
        }
        if (that.items.isSome() && this.items.isSome()) {
            if (that.items.get().size() + this.items.get().size() <= this.maxSize) {
                return this.items.get().addAll(that.items.get());
            }
            else {
                this.items = top;
                return true;
            }
        }
        else { /* sik is top */
            boolean wasNotTop = this.items.isSome();
            this.items = top;
            return wasNotTop;
        }

    }

    public Optional<Set<T>> maybeIterable() {
        return this.items;
    }

    public boolean isTop() {
        return !this.items.isSome();
    }

    public boolean isBottom() {
        return this.items.isSome() && this.items.get().isEmpty();
    }

    public boolean upperBounds(FiniteSet<T> that) {
        if (this.isTop()) {
            return true;
        } else if (that.isTop()) {
            return false;
        } else {
            return this.getSet().containsAll(that.getSet());
        }
    }

    public FiniteSet<T> copy() {
        return FiniteSet.make(this.maxSize, this.items);
    }

    public Set<T> getSet() {
        if (this.isTop()) {
            throw new RuntimeException("Cannot get set of Top");
        }
        else {
            return this.items.get();
        }
    }

    public int getMaxSize() {
        return this.maxSize;
    }

    public <U> FiniteSet<U> map(Function<? super T, U> f) {
        Optional<Set<U>> optionalResult;

        if (this.items.isNone()) {
            optionalResult = Optional.none();
        } else {
            Set<U> setResult = new HashSet<>();
            for (T item : this.items.get()) {
                setResult.add(f.apply(item));
            }
            optionalResult = Optional.some(setResult);
        }

        return new FiniteSet<>(this.maxSize, optionalResult);
    }

    public <U> FiniteSet<U> flatMap(Function<? super T, ? extends FiniteSet<U>> f) {
        if (this.items.isSome()) {
            Set<T> s = this.items.get();
            Set<U> a = new HashSet<>();
            for (T t : s) {
                FiniteSet<U> r = f.apply(t);
                if (r.items.isSome()) {
                    a.addAll(r.items.get());
                }
                else {
                    return new FiniteSet<>(this.maxSize, Optional.<Set<U>> none());
                }
            }
            return new FiniteSet<>(this.maxSize, Optional.some(a));
        }
        else {
            return new FiniteSet<>(this.maxSize, Optional.<Set<U>> none());
        }
    }

    @Override
    public String toString() {
        if (this.isTop()) {
            return "FS(⊤)";
        } else if (this.isBottom()) {
            return "FS(⊥)";
        } else {
            Set<T> ts = this.items.get();
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            Iterator<T> it = ts.iterator();
            if (it.hasNext()) {
                sb.append(stringify(it.next()));
                while (it.hasNext()) {
                    sb.append(", " + stringify(it.next()));
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
