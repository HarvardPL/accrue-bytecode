package util;

import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Workqueue where duplicate elements are not added
 * 
 * @param <T>
 *            type of queue elements
 */
public class WorkQueue<T> {

    /**
     * Internal Q
     */
    private Deque<T> q = new LinkedBlockingDeque<T>();
    /**
     * Mirror of Q used to quickly check containment
     */
    private Set<T> qSet = new HashSet<T>();

    /**
     * Add n to the back of the queue if it is not already there
     * 
     * @param n
     *            node to add
     * @return true if the node was not already in the queue
     */
    public boolean add(T n) {
        boolean notInQ = qSet.add(n);
        if (notInQ) {
            q.add(n);
        }
        return notInQ;
    }

    /**
     * Get the next result from the queue
     * 
     * @return the next result or null if the queue is empty
     */
    public T poll() {
        if (q.isEmpty()) {
            return null;
        }
        T n = q.poll();
        qSet.remove(n);
        return n;
    }

    /**
     * Add a set of nodes to the back of the queue.
     * 
     * @param ns
     *            nodes to add
     * @return true if the queue changed as a result of this call
     */
    public boolean addAll(Set<T> ns) {
        boolean changed = false;
        for (T n : ns) {
            changed |= add(n);
        }
        return changed;
    }

    /**
     * Add all the T's in the collection <code>i</code> is an iterator for
     * 
     * @param i
     *            iterator of T's
     * @return true if the queue changed as a result of this call
     */
    public boolean addAll(Iterator<T> i) {
        boolean changed = false;
        while (i.hasNext()) {
            changed |= add(i.next());
        }
        return changed;
    }

    /**
     * Check whether the queue is empty
     * 
     * @return true if the queue is empty
     */
    public boolean isEmpty() {
        return qSet.isEmpty();
    }

    @Override
    public String toString() {
        return q.toString();
    }
}
