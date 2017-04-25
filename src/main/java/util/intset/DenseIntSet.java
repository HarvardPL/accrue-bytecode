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

import java.util.NoSuchElementException;

import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.debug.UnimplementedError;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;

/**
 * A dense ordered, duplicate-free, fully-encapsulated set of integers; not necessary mutable
 */
public class DenseIntSet implements IntSet {

    // TODO: I'm not thrilled with exposing these to subclasses, but
    // it seems expedient for now.
    /**
     * The backing store of int arrays
     */
    protected int[] elementBitArray;

    /**
     * The number of entries in the backing store that are valid.
     */
    protected int size = 0;

    protected static final int intToBitArrayIndex(int i) {
        return i >> 5; // 2^5 = 32 = Integer.SIZE
    }

    protected static final int intToBitIndex(int i) {
        return i % Integer.SIZE;
    }

    protected DenseIntSet(int maxElem) {
        elementBitArray = new int[intToBitArrayIndex(maxElem) + 1];
    }

    /**
     * Subclasses should use this with extreme care. Do not allow the backing array to escape elsewhere.
     */
    protected DenseIntSet(int[] backingArray, int size) {
        if (backingArray == null) {
            throw new IllegalArgumentException("backingArray is null");
        }
        elementBitArray = backingArray;
        this.size = size;
    }

    /**
     * Subclasses should use this with extreme care.
     */
    public DenseIntSet() {
        elementBitArray = null;
        this.size = 0;
    }

    protected DenseIntSet(DenseIntSet S) {
        cloneState(S);
    }


    private void cloneState(DenseIntSet S) {
        if (S.elementBitArray != null) {
            elementBitArray = S.elementBitArray.clone();
        } else {
            elementBitArray = null;
        }
        this.size = S.size;
    }

    public DenseIntSet(IntSet S) throws IllegalArgumentException {
        if (S == null) {
            throw new IllegalArgumentException("S == null");
        }
        if (S instanceof DenseIntSet) {
            cloneState((DenseIntSet) S);
        } else {
            elementBitArray = new int[intToBitArrayIndex(S.max()) + 1];
            size = S.size();
            S.foreach(new IntSetAction() {
                @Override
                public void act(int i) {
                    setBit(i);
                }
            });
        }
    }

    protected boolean setBit(int i) {
        int arrayind = intToBitArrayIndex(i);
        int bitind = intToBitIndex(i);
        int x = this.elementBitArray[arrayind];
        int mask = (1 << bitind);
        boolean alreadySet = ((x & mask) != 0);
        if (!alreadySet) {
            x |= mask;
            this.elementBitArray[arrayind] = x;
            size++;
            return true;
        }
        return false;
    }

    protected boolean testBit(int i) {
        int arrayind = intToBitArrayIndex(i);
        int bitind = intToBitIndex(i);
        int x = this.elementBitArray[arrayind];
        int mask = (1 << bitind);
        return ((x & mask) != 0);
    }

    /**
     * Does this set contain value x?
     *
     * @see com.ibm.wala.util.intset.IntSet#contains(int)
     */
    @Override
    public final boolean contains(int x) {
        if (elementBitArray == null) {
            return false;
        }
        return testBit(x);
    }


    @Override
    public final int size() {
        return size;
    }

    @Override
    public final boolean isEmpty() {
        return size == 0;
    }


    private boolean sameValueInternal(DenseIntSet that) {
        if (size != that.size) {
            return false;
        } else {
            for (int i = 0; i < elementBitArray.length; i++) {
                if (elementBitArray[i] != that.elementBitArray[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public boolean sameValue(IntSet that) throws IllegalArgumentException, UnimplementedError {
        if (that == null) {
            throw new IllegalArgumentException("that == null");
        }
        if (that instanceof DenseIntSet) {
            return sameValueInternal((DenseIntSet) that);
        } else {
            Assertions.UNREACHABLE(that.getClass().toString());
            return false;
        }
    }

    /**
     * @return true iff <code>this</code> is a subset of <code>that</code>.
     *
     * Faster than: <code>this.diff(that) == EMPTY</code>.
     */
    private boolean isSubsetInternal(DenseIntSet that) {

        if (elementBitArray == null) {
            return true;
        }
        if (that.elementBitArray == null) {
            return false;
        }
        if (this.equals(that)) {
            return true;
        }
        if (this.sameValue(that)) {
            return true;
        }

        if (this.size > that.size) {
            return false;
        }
        for (int i = 0; i < this.elementBitArray.length; i++) {
            int b1 = this.elementBitArray[i];
            int b2 = that.elementBitArray[i];
            if ((b1 | b2) != b2) {
                return false;
            }
        }
        return true;
    }


    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer(6 * size);
        sb.append("{ ");
        if (elementBitArray != null && elementBitArray.length > 0) {
            int ii = 0;
            int arrayIndex = 0;
            int bitIndex = 0;
            int currentInt = elementBitArray[0];
            int currentMask = 1;
            while (ii <= this.max()) {
                if ((currentInt & currentMask) != 0) {
                    sb.append(ii);
                    sb.append(" ");
                }
                ii++;
                if (++bitIndex % Integer.SIZE == 0) {
                    bitIndex = 0;
                    currentInt = elementBitArray[++arrayIndex];
                    currentMask = 1;
                }
                else {
                    currentMask <<= 1;
                }
            }
        }
        sb.append("}");
        return sb.toString();
    }


    @Override
    public IntSet intersection(IntSet that) {
        if (that == null) {
            throw new IllegalArgumentException("that == null");
        }
        throw new UnsupportedOperationException();
    }

    /*
     * @see com.ibm.wala.util.intset.IntSet#union(com.ibm.wala.util.intset.IntSet)
     */
    @Override
    public IntSet union(IntSet that) {
        MutableDenseIntSet temp = new MutableDenseIntSet();
        temp.addAll(this);
        temp.addAll(that);

        return temp;
    }

    /*
     * @see com.ibm.wala.util.intset.IntSet#iterator()
     */
    @Override
    public IntIterator intIterator() {
        return new IntIterator() {
            int val = -1;
            boolean valid = false;

            @Override
            public boolean hasNext() {
                while (!valid && val <= max()) {
                    val++;
                    if (testBit(val)) {
                        valid = true;
                    }
                }
                return valid;
            }

            @Override
            public int next() throws NoSuchElementException {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                assert valid;
                valid = false;
                return val;
            }
        };
    }

    /**
     * @return the largest element in the set
     */
    @Override
    public final int max() throws IllegalStateException {
        if (elementBitArray == null) {
            throw new IllegalStateException("Illegal to ask max() on an empty int set");
        }
        if (size == 0) {
            return -1;
        }
        int ii = this.elementBitArray.length * Integer.SIZE - 1;
        while (true) {
            if (testBit(ii)) {
                return ii;
            }
            ii--;
        }
    }

    /*
     * @see com.ibm.wala.util.intset.IntSet#foreach(com.ibm.wala.util.intset.IntSetAction)
     */
    @Override
    public void foreach(IntSetAction action) {
        if (action == null) {
            throw new IllegalArgumentException("null action");
        }
        if (elementBitArray != null && elementBitArray.length > 0) {
            int ii = 0;
            int arrayIndex = 0;
            int bitIndex = 0;
            int currentInt = elementBitArray[0];
            int currentMask = 1;
            while (ii <= this.max()) {
                if ((currentInt & currentMask) != 0) {
                    action.act(ii);
                }
                ii++;
                if (++bitIndex % Integer.SIZE == 0) {
                    bitIndex = 0;
                    currentInt = elementBitArray[++arrayIndex];
                    currentMask = 1;
                }
                else {
                    currentMask <<= 1;
                }
            }
        }
    }

    /*
     * @see com.ibm.wala.util.intset.IntSet#foreach(com.ibm.wala.util.intset.IntSetAction)
     */
    @Override
    public void foreachExcluding(IntSet X, IntSetAction action) {
        if (action == null) {
            throw new IllegalArgumentException("null action");
        }
        if (elementBitArray != null && elementBitArray.length > 0) {
            int ii = 0;
            int arrayIndex = 0;
            int bitIndex = 0;
            int currentInt = elementBitArray[0];
            int currentMask = 1;
            while (ii <= this.max()) {
                if ((currentInt & currentMask) != 0 && !X.contains(ii)) {
                    action.act(ii);
                }
                ii++;
                if (++bitIndex % Integer.SIZE == 0) {
                    bitIndex = 0;
                    currentInt = elementBitArray[++arrayIndex];
                    currentMask = 1;
                }
                else {
                    currentMask <<= 1;
                }
            }
        }
    }

    /*
     * @see com.ibm.wala.util.intset.IntSet#isSubset(com.ibm.wala.util.intset.IntSet)
     */
    @Override
    public boolean isSubset(IntSet that) {
        if (that == null) {
            throw new IllegalArgumentException("null that");
        }
        if (that instanceof DenseIntSet) {
            return isSubsetInternal((DenseIntSet) that);
        } else {
            // really slow. optimize as needed.
            for (IntIterator it = intIterator(); it.hasNext();) {
                if (!that.contains(it.next())) {
                    return false;
                }
            }
            return true;
        }
    }

    /*
     * @see com.ibm.wala.util.intset.IntSet#containsAny(com.ibm.wala.util.intset.IntSet)
     */
    @Override
    public boolean containsAny(IntSet set) {
        if (set instanceof DenseIntSet) {
            return containsAny(set);
        } else {
            for (IntIterator it = intIterator(); it.hasNext();) {
                if (set.contains(it.next())) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean containsAny(DenseIntSet set) throws IllegalArgumentException {
        if (set == null) {
            throw new IllegalArgumentException("set == null");
        }
        int i = 0;
        for (int j = 0; j < set.elementBitArray.length && j < this.elementBitArray.length; j++) {
            if ((this.elementBitArray[j] & set.elementBitArray[j]) != 0) {
                return true;
            }
        }
        return false;
    }

}
