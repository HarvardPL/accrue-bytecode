package util.intmap;

import com.ibm.wala.util.intset.IntIterator;

/**
 * Map of integers to (non-null) objects. Based on WALA IntSet implementation.
 * 
 */
public interface IntMap<T> {

    /**
     * @return true iff this map contains integer i as a key
     */
    public boolean containsKey(int i);

    /**
     * @return the object the key i maps to, null i is not a key.
     */
    public T get(int i);

    /**
     * Map the key i to the value val, returning the old value that i mapped to, if any.
     */
    public T put(int i, T val);

    /**
     * @return true iff this map is empty
     */
  public boolean isEmpty();

    /**
     * @return the number of keys in this map
     */
  public int size();

  /**
   * @return a perhaps more efficient iterator
   */
    public IntIterator keyIterator();


    /**
     * @return maximum integer key in this map.
     */
  public int max();

}