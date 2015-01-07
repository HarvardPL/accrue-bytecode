/*******************************************************************************
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package util.intset;

import java.util.Arrays;

import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * A dense ordered, mutable duplicate-free, fully-encapsulated set of integers. Instances are not canonical, except for
 * EMPTY.
 *
 */
public class MutableDenseIntSet extends DenseIntSet implements MutableIntSet {

    protected MutableDenseIntSet(IntSet set) {
        super();
        copySet(set);
    }

    protected MutableDenseIntSet(int[] backingStore, int size) {
        super(backingStore, size);
    }

    /**
     * Create an empty set with a non-zero capacity
     */
    private MutableDenseIntSet(int initialCapacity) throws IllegalArgumentException {
        super(initialCapacity);
        size = 0;
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
    }

    protected MutableDenseIntSet() {
        super();
    }

    @Override
    public void clear() {
        size = 0;
        Arrays.fill(elementBitArray, 0);
    }

    /**
     */
    @Override
    public boolean remove(int value) {
        if (elementBitArray != null) {
            if (value <= this.max()) {
                return unsetBit(value);
            }
        }
        return false;
    }

    protected boolean unsetBit(int i) {
        int arrayind = intToBitArrayIndex(i);
        int bitind = intToBitIndex(i);
        int x = this.elementBitArray[arrayind];
        int mask = (1 << bitind);
        boolean alreadySet = ((x & mask) != 0);
        if (alreadySet) {
            x &= (~mask);
            size--;
            this.elementBitArray[arrayind] = x;
            return true;
        }
        return false;
    }

    /**
     * @param value
     * @return true iff this value changes
     */
    @Override
    @SuppressWarnings("unused")
    public boolean add(int value) {
        if (elementBitArray == null) {
            int maxElem = Math.max(getInitialMaxElement(), value);
            elementBitArray = new int[intToBitArrayIndex(maxElem) + 1];
            size = 1;
            setBit(value);
            return true;
        }
        else {
            if (intToBitArrayIndex(value) >= this.elementBitArray.length) {
                // expand the array
                extendCapacity(value);
                setBit(value);
                return true;
            }
            return setBit(value);
        }
    }

    protected int getInitialMaxElement() {
        return Integer.SIZE * 16;
    }

    /**
     * @throws IllegalArgumentException if that == null
     */
    @Override
    @SuppressWarnings("unused")
    public void copySet(IntSet that) throws IllegalArgumentException {
    }

    @Override
    public void intersectWith(IntSet set) {
        if (set == null) {
            throw new IllegalArgumentException("null set");
        }
        if (set instanceof DenseIntSet) {
            intersectWith((DenseIntSet)set);
        }
        else {
            throw new UnsupportedOperationException();
        }
    }

    private void intersectWith(DenseIntSet set) {
        throw new UnsupportedOperationException();
    }

    /**
     * Add all elements from another int set.
     *
     * @return true iff this set changes
     * @throws IllegalArgumentException if set == null
     */
    @Override
    @SuppressWarnings("unused")
    public boolean addAll(IntSet set) throws IllegalArgumentException {
        if (set == null) {
            throw new IllegalArgumentException("set == null");
        }
        if (set instanceof DenseIntSet) {
            return addAll(set);
        }
        else {
            int oldSize = size;
            int setMax = set.max();
            if (setMax > this.max()) {
                extendCapacity(setMax);
            }
            set.foreach(new IntSetAction() {
                @Override
                public void act(int i) {
                    if (!contains(i)) {
                        add(i);
                    }
                }
            });

            return size != oldSize;

        }
    }


    private void extendCapacity(int newMax) {
        int newLength = intToBitArrayIndex(newMax) + 1;
        if (newLength <= this.elementBitArray.length) {
            return;
        }
        int[] tmp = new int[newLength];
        System.arraycopy(elementBitArray, 0, tmp, 0, elementBitArray.length);
        this.elementBitArray = tmp;
    }

    /*
     * @see
     * com.ibm.wala.util.intset.MutableIntSet#addAllInIntersection(com.ibm.wala
     * .util.intset.IntSet, com.ibm.wala.util.intset.IntSet)
     */
    @Override
    public boolean addAllInIntersection(IntSet other, IntSet filter) {
        throw new UnsupportedOperationException();
    }


    public static MutableDenseIntSet makeEmpty() {
        return new MutableDenseIntSet();
    }

    public static MutableDenseIntSet createMutableDenseIntSet(int maxElem) throws IllegalArgumentException {
        if (maxElem < 0) {
            throw new IllegalArgumentException("illegal maxElem: " + maxElem);
        }
        return new MutableDenseIntSet(maxElem);
    }

    @Override
    public boolean equals(Object o) {
        throw new UnsupportedOperationException();
    }

}
