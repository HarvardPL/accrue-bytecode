package util;

import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Work queue where duplicate elements are not added and containment checks are
 * fast.
 * 
 * @param <T>
 *            type of queue elements
 */
public class WorkQueue<T> implements Collection<T> {

    /**
     * Internal Q
     */
    private Deque<T> q = new LinkedBlockingDeque<>();
    /**
     * Mirror of Q used to quickly check containment
     */
    private Set<T> qSet = new HashSet<>();

    /**
     * Create an empty queue
     */
    public WorkQueue() {
    }

    /**
     * Create a queue containing all the elements in the given collection
     * 
     * @param c
     *            initial elements of the queue
     */
    public WorkQueue(Collection<T> c) {
        this.addAll(c);
    }

    /**
     * Add n to the back of the queue if it is not already there
     * 
     * @param n
     *            node to add
     * @return true if the node was not already in the queue
     */
    @Override
    public boolean add(T n) {
        boolean notInQ = qSet.add(n);
        if (notInQ) {
            q.add(n);
        }
        return notInQ;
    }

    /**
     * Get and removethe next result from the queue
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
     * Add a collection of nodes to the back of the queue.
     * 
     * @param collection
     *            nodes to add
     * @return true if the queue changed as a result of this call
     */
    @Override
    public boolean addAll(Collection<? extends T> collection) {
        boolean changed = false;
        for (T n : collection) {
            changed |= add(n);
        }
        return changed;
    }

    @Override
    public boolean isEmpty() {
        return qSet.isEmpty();
    }

    @Override
    public String toString() {
        return q.toString();
    }

    @Override
    public boolean contains(Object element) {
        return qSet.contains(element);
    }

    @Override
    public int size() {
        return qSet.size();
    }

    @Override
    public Iterator<T> iterator() {
        return q.iterator();
    }

    @Override
    public Object[] toArray() {
        return qSet.toArray();
    }

    @Override
    public <S> S[] toArray(S[] a) {
        return qSet.toArray(a);
    }

    @Override
    public boolean remove(Object o) {
        q.remove();
        return qSet.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return qSet.containsAll(c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        q.removeAll(c);
        return qSet.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        q.retainAll(c);
        return qSet.retainAll(c);
    }

    @Override
    public void clear() {
        q.clear();
        qSet.clear();
    }
}
