package util;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Create a set with all the elements from an iterator
 */
public class IteratorSet {

    /**
     * Cannot instantiate this class
     */
    private IteratorSet() {
        // intentionally blank
    };

    /**
     * Make a new set containing all the elements of the given iterator
     * 
     * @param iter
     *            iterator to take elements from
     * @return new set containing elements in iter
     */
    public static <E> Set<E> make(Iterator<E> iter) {
        Set<E> s = new LinkedHashSet<>();
        while (iter.hasNext()) {
            s.add(iter.next());
        }
        return s;
    }
}
