package util;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable pair of the given types.
 * 
 * @param <F>
 *            Type of the first element of the pair
 * @param <S>
 *            Type of the second element of the pair
 */
public class OrderedPair<F, S> {

    /**
     * first element
     */
    private final F fst;
    /**
     * second element
     */
    private final S snd;
    private final int memoizedHashCode;

    /**
     * Create a pair from the two given states
     * 
     * @param fst
     *            first state of the pair
     * @param snd
     *            second state of the pair
     */
    public OrderedPair(F fst, S snd) {
        this.fst = fst;
        this.snd = snd;
        this.memoizedHashCode = computeHashCode();
    }

    /**
     * Get the first element of the pair
     * 
     * @return first element
     */
    public F fst() {
        return fst;
    }

    /**
     * Get the second element of the pair
     * 
     * @return S second element
     */
    public S snd() {
        return snd;
    }

    /**
     * Get a set of all pairs with the first element in the first set and the
     * second element in the second set
     * 
     * @param firstSet
     *            set of F's
     * @param secondSet
     *            set of S's
     * @return set of pairs of type (F,S)
     * 
     * @param <F>
     *            type of first element
     * @param <S>
     *            type of second element
     */
    public static <F, S> Set<OrderedPair<F, S>> makeAllPairs(Set<F> firstSet, Set<S> secondSet) {
        Set<OrderedPair<F, S>> pairs = new HashSet<>();
        for (F fst : firstSet) {
            for (S snd : secondSet) {
                pairs.add(new OrderedPair<>(fst, snd));
            }
        }
        return pairs;
    }

    /**
     * Get a Hash map where the key is the first item and the value is the
     * second
     * 
     * @param list
     *            list of pairs to create the map from
     * @return new map from first items to second items
     * 
     * @param <F>
     *            type of first item
     * @param <S>
     *            type of second item
     */
    public static <F, S> Map<F, S> listToMap(List<OrderedPair<F, S>> list) {
        LinkedHashMap<F, S> map = new LinkedHashMap<>();
        for (OrderedPair<F, S> p : list) {
            map.put(p.fst, p.snd);
        }
        return map;
    }

    /**
     * Make a set of pairs {(c,c) | c is in the given set}
     * 
     * @param set
     *            set of C's
     * @param <C>
     *            type of elements in the set
     * 
     * @return set of pairs of type (C,C)
     */
    public static <C> Set<OrderedPair<C, C>> makeIndenticalPairs(Set<C> set) {
        Set<OrderedPair<C, C>> newSet = new HashSet<>();

        for (C c : set) {
            newSet.add(new OrderedPair<>(c, c));
        }

        return newSet;
    }

    /**
     * Swap the elements of this pair
     * 
     * @return a pair with the elements swapped
     */
    public OrderedPair<S, F> swap() {
        return new OrderedPair<>(snd(), fst());
    }

    /**
     * Two {@link OrderedPair}s are equal if their
     * constituent parts are equal.
     * <p>
     * 
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof OrderedPair<?, ?>)) {
            return false;
        }
        // We have to use ? since we cannot check the generic type at runtime
        OrderedPair<?, ?> other = (OrderedPair<?, ?>) obj;
        return (fst() == null ? other.fst() == null : fst().equals(other.fst()))
                && (snd() == null ? other.snd() == null : snd().equals(other.snd()));
    }

    /**
     * Compute the hash code once
     * 
     * @return hash code
     */
    private int computeHashCode() {
        int firstHash = fst() != null ? fst().hashCode() : 0;
        // flip the bits so (b,a) has a different hash than (a,b)
        return (firstHash >>> 16 | firstHash << 16) ^ (snd() != null ? snd().hashCode() : 0);
    }

    @Override
    public int hashCode() {
        return memoizedHashCode;
    }

    @Override
    public String toString() {
        return new String("(" + fst() + ", " + snd() + ")");
    }
}
