package signatures.library.java.lang;




/**
 * Signature for java.lang.AbstractStringBuilder
 */
abstract class AbstractStringBuilder implements Appendable, CharSequence {

    /**
     * Represents the state of the string builder
     */
    int count;

    AbstractStringBuilder() {
    }

    AbstractStringBuilder(int capacity) {
        count = MockNativeMethods.intFunctionOf(capacity);
    }


    @Override
    public int length() {
        return MockNativeMethods.intFunctionOf(count);
    }

    public int capacity() {
        return MockNativeMethods.intFunctionOf(count);
    }

    public void ensureCapacity(int minimumCapacity) {
        this.count = MockNativeMethods.intFunctionOf(this.count, minimumCapacity);
    }

    void expandCapacity(int minimumCapacity) {
        this.count = MockNativeMethods.intFunctionOf(this.count, minimumCapacity);
    }

    public void trimToSize() {
        // Intentional
    }

    public void setLength(int newLength) {
        this.count = MockNativeMethods.intFunctionOf(this.count, newLength);
    }

    @Override
    public char charAt(int index) {
        return (char) MockNativeMethods.intFunctionOf(index, this.count);
    }

    public int codePointAt(int index) {
        return MockNativeMethods.intFunctionOf(index, this.count);
    }

    public int codePointBefore(int index) {
        return MockNativeMethods.intFunctionOf(index, this.count);
    }

    public int codePointCount(int beginIndex, int endIndex) {
        return MockNativeMethods.intFunctionOf(beginIndex, endIndex, this.count);
    }

    public int offsetByCodePoints(int index, int codePointOffset) {
        return MockNativeMethods.intFunctionOf(index, codePointOffset, this.count);
    }

    public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
        dst[0] = (char) MockNativeMethods.intFunctionOf(srcBegin, srcEnd, dstBegin, this.count);
    }

    public void setCharAt(int index, char ch) {
        count = MockNativeMethods.intFunctionOf(this.count, index, ch);
    }

    public AbstractStringBuilder append(Object obj) {
        return append(String.valueOf(obj));
    }

    public AbstractStringBuilder append(String str) {
        this.count = MockNativeMethods.intFunctionOf(this.count, str.count);
        return this;
    }

    public AbstractStringBuilder append(StringBuffer sb) {
        this.count = MockNativeMethods.intFunctionOf(this.count, String.valueOf(sb).count);
        return this;
    }

    @Override
    public AbstractStringBuilder append(CharSequence s) {
        this.count = MockNativeMethods.intFunctionOf(this.count, String.valueOf(s).count);
        return this;
    }

    @Override
    public AbstractStringBuilder append(CharSequence s, int start, int end) {
        this.count = MockNativeMethods.intFunctionOf(this.count, String.valueOf(s).count, start, end);
        return this;
    }

    public AbstractStringBuilder append(char str[]) {
        this.count = MockNativeMethods.intFunctionOf(this.count, str[0]);
        return this;
    }

    public AbstractStringBuilder append(char str[], int offset, int len) {
        this.count = MockNativeMethods.intFunctionOf(this.count, str[0], offset, len);
        return this;
    }

    public AbstractStringBuilder append(boolean b) {
        this.count = MockNativeMethods.intFunctionOf(this.count, b);
        return this;
    }

    @Override
    public AbstractStringBuilder append(char c) {
        this.count = MockNativeMethods.intFunctionOf(this.count, c);
        return this;
    }

    public AbstractStringBuilder append(int i) {
        this.count = MockNativeMethods.intFunctionOf(this.count, i);
        return this;
    }

    // No signature for these should be fine the way they are
    //    final static int[] sizeTable = { 9, 99, 999, 9999, 99999, 999999, 9999999, 99999999, 999999999, Integer.MAX_VALUE };
    //
    //    // Requires positive x
    //    static int stringSizeOfInt(int x) {
    //        for (int i = 0;; i++) {
    //            if (x <= sizeTable[i]) {
    //                return i + 1;
    //            }
    //        }
    //    }

    public AbstractStringBuilder append(long l) {
        this.count = MockNativeMethods.intFunctionOf(this.count, (int) l);
        return this;
    }

    // No signature for this should be fine the way they are
    //    // Requires positive x
    //    static int stringSizeOfLong(long x) {
    //        long p = 10;
    //        for (int i = 1; i < 19; i++) {
    //            if (x < p) {
    //                return i;
    //            }
    //            p = 10 * p;
    //        }
    //        return 19;
    //    }

    public AbstractStringBuilder append(float f) {
        this.count = MockNativeMethods.intFunctionOf(this.count, (int) f);
        return this;
    }

    public AbstractStringBuilder append(double d) {
        this.count = MockNativeMethods.intFunctionOf(this.count, (int) d);
        return this;
    }

    public AbstractStringBuilder delete(int start, int end) {
        this.count = MockNativeMethods.intFunctionOf(this.count, start, end);
        return this;
    }

    public AbstractStringBuilder appendCodePoint(int codePoint) {
        this.count = MockNativeMethods.intFunctionOf(this.count, codePoint);
        return this;
    }

    public AbstractStringBuilder deleteCharAt(int index) {
        this.count = MockNativeMethods.intFunctionOf(this.count, index);
        return this;
    }

    public AbstractStringBuilder replace(int start, int end, String str) {
        this.count = MockNativeMethods.intFunctionOf(this.count, start, end, str.count);
        return this;
    }

    public String substring(int start) {
        return String.valueOf(MockNativeMethods.intFunctionOf(this.count, start));
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return substring(start, end);
    }

    public String substring(int start, int end) {
        return String.valueOf(MockNativeMethods.intFunctionOf(this.count, start, end));
    }

    public AbstractStringBuilder insert(int index, char str[], int offset, int len) {
        this.count = MockNativeMethods.intFunctionOf(this.count, offset, len, index, str[0]);
        return this;
    }

    public AbstractStringBuilder insert(int offset, Object obj) {
        return insert(offset, String.valueOf(obj));
    }

    public AbstractStringBuilder insert(int offset, String str) {
        this.count = MockNativeMethods.intFunctionOf(this.count, offset, str.count);
        return this;
    }

    public AbstractStringBuilder insert(int offset, char str[]) {
        this.count = MockNativeMethods.intFunctionOf(this.count, offset, str[0]);
        return this;
    }

    public AbstractStringBuilder insert(int dstOffset, CharSequence s) {
        if (s == null) {
            s = "null";
        }
        if (s instanceof String) {
            return this.insert(dstOffset, (String)s);
        }
        return this.insert(dstOffset, s, 0, s.length());
    }

    public AbstractStringBuilder insert(int dstOffset, CharSequence s, int start, int end) {
        this.count = MockNativeMethods.intFunctionOf(this.count, dstOffset, String.valueOf(s).count, start, end);
        return this;
    }

    public AbstractStringBuilder insert(int offset, boolean b) {
        return insert(offset, String.valueOf(b));
    }

    public AbstractStringBuilder insert(int offset, char c) {
        this.count = MockNativeMethods.intFunctionOf(this.count, offset, c);
        return this;
    }

    public AbstractStringBuilder insert(int offset, int i) {
        return insert(offset, String.valueOf(i));
    }

    public AbstractStringBuilder insert(int offset, long l) {
        return insert(offset, String.valueOf(l));
    }

    public AbstractStringBuilder insert(int offset, float f) {
        return insert(offset, String.valueOf(f));
    }

    public AbstractStringBuilder insert(int offset, double d) {
        return insert(offset, String.valueOf(d));
    }

    public int indexOf(String str) {
        return indexOf(str, 0);
    }

    public int indexOf(String str, int fromIndex) {
        return MockNativeMethods.intFunctionOf(this.count, str.count, fromIndex);
    }

    public int lastIndexOf(String str) {
        return lastIndexOf(str, count);
    }

    public int lastIndexOf(String str, int fromIndex) {
        return MockNativeMethods.intFunctionOf(this.count, str.count, fromIndex);
    }

    public AbstractStringBuilder reverse() {
        this.count = MockNativeMethods.intFunctionOf(this.count);
        return this;
    }

    /**
     * Needed by <tt>String</tt> for the contentEquals method.
     */
    final char[] getValue() {
        return new char[] { (char) count };
    }

}