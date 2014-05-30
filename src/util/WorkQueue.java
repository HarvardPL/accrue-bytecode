package util;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import com.ibm.wala.ipa.callgraph.CGNode;

/**
 * Work queue where duplicate elements are not added and containment checks are
 * fast.
 * 
 * @param <T>
 *            type of queue elements
 */
public class WorkQueue<T> {

    /**
     * Internal Q
     */
    private LinkedList<T> q = new LinkedList<>();
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
    public boolean add(T n) {
        boolean notInQ = qSet.add(n);
        if (notInQ) {
            q.addLast(n);
        }
        return notInQ;
    }

    /**
     * Get and remove the next result from the queue
     * 
     * @return the next result or null if the queue is empty
     */
    public T poll() {
        if (q.isEmpty()) {
            return null;
        }
        T n = q.removeFirst();
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
    public boolean addAll(Collection<? extends T> collection) {
        boolean changed = false;
        for (T n : collection) {
            changed |= add(n);
        }
        return changed;
    }

    /**
     * Add a collection of nodes to the back of the queue.
     * 
     * @param collection
     *            nodes to add
     * @return true if the queue changed as a result of this call
     */
    public boolean addAll(WorkQueue<T> wq) {
        return this.addAll(wq.qSet);
    }

    public boolean isEmpty() {
        return qSet.isEmpty();
    }

    @Override
    public String toString() {
        return q.toString();
    }

    public boolean contains(CGNode succ) {
        return qSet.contains(succ);
    }
}
