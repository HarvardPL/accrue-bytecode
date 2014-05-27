package analysis.pointer.analyses;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.ContextKey;

/**
 * Immutable stack of elements with operations that can ensure a maximum number of elements
 */
class ContextStack<E> implements Iterable<E>, Context {

    /**
     * Key to that can be used get the internal stack
     */
    public static final ContextKey ELEMENTS = new ContextKey() {
        @Override
        public String toString() {
            return "ELEMENTS_KEY";
        }
    };

    /**
     * Empty array of elements
     */
    private static final Object[] EMPTY_ARRAY = {};

    /**
     * Singleton empty stack
     */
    private static final ContextStack<?> EMPTY_STACK = HeapAbstractionFactory.memoize(new ContextStack<>(EMPTY_ARRAY),
                                    Arrays.asList(EMPTY_ARRAY));

    /**
     * Array of elements
     */
    private final E[] elements;

    /**
     * Get a stack with no elements
     * 
     * @return empty stack
     */
    @SuppressWarnings("unchecked")
    public static <E> ContextStack<E> emptyStack() {
        return (ContextStack<E>) EMPTY_STACK;
    }

    /**
     * Create a new stack from the given elements
     * 
     * @param elements
     *            elements in the stack
     */
    protected ContextStack(E[] elements) {
        this.elements = elements;
    }

    /**
     * Add the element to the top of the stack, dropping elements off the end if necessary to get the correct depth.
     * 
     * @param e
     *            element to push onto the stack
     * @param depth
     *            max number of elements in the stack
     * @return new stack with size less than or equal to <code>depth</code> the given element pushed on
     */
    public ContextStack<E> push(E e, int depth) {
        @SuppressWarnings("unchecked")
        E[] newElements = (E[]) new Object[depth];
        newElements[0] = e;

        System.arraycopy(elements, 0, newElements, 1, Math.min(elements.length, depth - 1));
        return HeapAbstractionFactory.memoize(new ContextStack<>(newElements), Arrays.asList(newElements));
    }

    /**
     * Truncate the stack (i.e., remove elements from the bottom of the stack, the least recently pushed) so that the
     * size is at most <code>depth</code>
     * 
     * @param depth
     *            maximum size of the stack
     * @return If this has less than depth elements then this, otherwise a new stack that contains the
     *         <code>depth</code> most recent elements
     */
    public ContextStack<E> truncate(int depth) {
        if (depth > elements.length) {
            return this;
        }
        // We need to truncate, create a new array since these stacks are immutable
        @SuppressWarnings("unchecked")
        E[] newElements = (E[]) new Object[depth];

        System.arraycopy(elements, 0, newElements, 0, Math.min(elements.length, depth));
        return HeapAbstractionFactory.memoize(new ContextStack<>(newElements), Arrays.asList(newElements));
    }

    @Override
    public String toString() {
        return Arrays.toString(elements);
    }

    @Override
    public Iterator<E> iterator() {
        return Collections.unmodifiableList(Arrays.asList(elements)).iterator();
    }

    @Override
    public ContextItem get(ContextKey name) {
        if (ELEMENTS.equals(name)) {
            return new ContextItem.Value<>(this.elements);
        }
        return null;
    }
}
