package signatures.library.java.lang;




/**
 * Signature for java.lang.AbstractStringBuilder
 */
abstract class AbstractStringBuilder implements /*Appendable,*/CharSequence {

    /**
     * Represents the state of the string builder
     */
    int count;

    AbstractStringBuilder() {
    }

    AbstractStringBuilder(int capacity) {
        count = (capacity);
    }


    @Override
    public int length() {
        return (count);
    }

    public int capacity() {
        return (count);
    }

    public void ensureCapacity(int minimumCapacity) {
        this.count = this.count + minimumCapacity;
    }

    void expandCapacity(int minimumCapacity) {
        this.count = this.count + minimumCapacity;
    }

    public void trimToSize() {
        this.count = this.count + 42;
    }

    public void setLength(int newLength) {
        this.count = this.count + newLength;
    }

    @Override
    public char charAt(int index) {
        if ((index < 0) || (index >= count)) {
            throw new StringIndexOutOfBoundsException(index);
        }
        return (char) (index + this.count);
    }

    public int codePointAt(int index) {
        if ((index < 0) || (index >= count)) {
            throw new StringIndexOutOfBoundsException(index);
        }
        return index + this.count;
    }

    public int codePointBefore(int index) {
        if ((index < 0) || (index >= count)) {
            throw new StringIndexOutOfBoundsException(index);
        }
        return index + this.count;
    }

    public int codePointCount(int beginIndex, int endIndex) {
        if (beginIndex < 0 || endIndex > count || beginIndex > endIndex) {
            throw new IndexOutOfBoundsException();
        }
        return beginIndex + endIndex + this.count;
    }

    public int offsetByCodePoints(int index, int codePointOffset) {
        if (index < 0 || index > count) {
            throw new IndexOutOfBoundsException();
        }
        return index + codePointOffset + this.count;
    }

    public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
        if (srcBegin < 0) {
            throw new StringIndexOutOfBoundsException(srcBegin);
        }
        if ((srcEnd < 0) || (srcEnd > count)) {
            throw new StringIndexOutOfBoundsException(srcEnd);
        }
        if (srcBegin > srcEnd) {
            throw new StringIndexOutOfBoundsException("srcBegin > srcEnd");
        }
        dst[0] = (char) (srcBegin + srcEnd + dstBegin + this.count);
    }

    public void setCharAt(int index, char ch) {
        if ((index < 0) || (index >= count)) {
            throw new StringIndexOutOfBoundsException(index);
        }
        count = this.count + index + ch;
    }

    //    public AbstractStringBuilder append(Object obj) {
    //        return append(String.valueOf(obj));
    //    }

    public AbstractStringBuilder append(String str) {
        this.count = this.count + str.length();
        return this;
    }

    public AbstractStringBuilder append(StringBuffer sb) {
        this.count = this.count + sb.count;
        return this;
    }

    //    public AbstractStringBuilder append(CharSequence s) {
    //        if (s == null)
    //            s = "null";
    //        if (s instanceof String)
    //            return this.append((String)s);
    //        if (s instanceof StringBuffer)
    //            return this.append((StringBuffer)s);
    //        return this.append(s, 0, s.length());
    //    }

    public AbstractStringBuilder append(CharSequence s, int start, int end) {
        if (s == null) {
            s = "null";
        }
        if ((start < 0) || (end < 0) || (start > end) || (end > s.length())) {
            throw new IndexOutOfBoundsException("start " + start + ", end " + end + ", s.length() " + s.length());
        }
        this.count = this.count + start + end;
        for (int i = 0; i < s.length(); i++) {
            this.count += s.charAt(i);
        }
        return this;
    }

    public AbstractStringBuilder append(char str[]) {
        this.count = (this.count + str[0]);
        return this;
    }

    public AbstractStringBuilder append(char str[], int offset, int len) {
        this.count = this.count + str[0] + offset + len;
        return this;
    }

    public AbstractStringBuilder append(boolean b) {
        this.count = this.count + (b ? 0 : 1);
        return this;
    }

    public AbstractStringBuilder append(char c) {
        this.count = this.count + c;
        return this;
    }

    public AbstractStringBuilder append(int i) {
        this.count = this.count + i;
        return this;
    }

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
        this.count = this.count + (int) l;
        return this;
    }

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
        this.count = this.count + (int) f;
        return this;
    }

    public AbstractStringBuilder append(double d) {
        this.count = this.count + (int) d;
        return this;
    }

    public AbstractStringBuilder delete(int start, int end) {
        if (start < 0) {
            throw new StringIndexOutOfBoundsException(start);
        }
        if (end > count) {
            end = count;
        }
        if (start > end) {
            throw new StringIndexOutOfBoundsException();
        }
        this.count = this.count + start + end;
        return this;
    }

    public AbstractStringBuilder appendCodePoint(int codePoint) {
        if (!Character.isValidCodePoint(codePoint)) {
            throw new IllegalArgumentException();
        }
        this.count = this.count + codePoint;
        return this;
    }

    public AbstractStringBuilder deleteCharAt(int index) {
        if ((index < 0) || (index >= count)) {
            throw new StringIndexOutOfBoundsException(index);
        }
        this.count = this.count + index;
        return this;
    }

    public AbstractStringBuilder replace(int start, int end, String str) {
        if (start < 0) {
            throw new StringIndexOutOfBoundsException(start);
        }
        if (start > count) {
            throw new StringIndexOutOfBoundsException("start > length()");
        }
        if (start > end) {
            throw new StringIndexOutOfBoundsException("start > end");
        }

        this.count = this.count + start + end + str.length();
        return this;
    }

    public String substring(int start) {
        if (start < 0) {
            throw new StringIndexOutOfBoundsException(start);
        }
        return String.valueOf(this.count + start);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return substring(start, end);
    }

    public String substring(int start, int end) {
        if (start < 0) {
            throw new StringIndexOutOfBoundsException(start);
        }
        if (end > count) {
            throw new StringIndexOutOfBoundsException(end);
        }
        if (start > end) {
            throw new StringIndexOutOfBoundsException(end - start);
        }
        return String.valueOf(this.count + start + end);
    }

    public AbstractStringBuilder insert(int index, char str[], int offset, int len) {
        if ((index < 0) || (index > length())) {
            throw new StringIndexOutOfBoundsException(index);
        }
        if ((offset < 0) || (len < 0) || (offset > str.length - len)) {
            throw new StringIndexOutOfBoundsException(
                "offset " + offset + ", len " + len + ", str.length "
                + str.length);
        }
        this.count = (this.count + offset + len + index + str[0]);
        return this;
    }

    //    public AbstractStringBuilder insert(int offset, Object obj) {
    //        return insert(offset, String.valueOf(obj));
    //    }

    public AbstractStringBuilder insert(int offset, String str) {
        if ((offset < 0) || (offset > length())) {
            throw new StringIndexOutOfBoundsException(offset);
        }
        this.count = this.count + offset + str.length();
        return this;
    }

    public AbstractStringBuilder insert(int offset, char str[]) {
        if ((offset < 0) || (offset > length())) {
            throw new StringIndexOutOfBoundsException(offset);
        }
        this.count = this.count + offset + str[0];
        return this;
    }

    public AbstractStringBuilder insert(int dstOffset, CharSequence s) {
        if (s == null) {
            s = "null";
        }
        if (s instanceof String) {
            return this.insert(dstOffset, (String) s);
        }
        return this.insert(dstOffset, s, 0, s.length());
    }

    public AbstractStringBuilder insert(int dstOffset, CharSequence s, int start, int end) {
        if ((dstOffset < 0) || (dstOffset > this.length())) {
            throw new IndexOutOfBoundsException("dstOffset " + dstOffset);
        }
        if ((start < 0) || (end < 0) || (start > end) || (end > s.length())) {
            throw new IndexOutOfBoundsException(
                "start " + start + ", end " + end + ", s.length() "
                + s.length());
        }
        this.count = this.count + dstOffset + start + end;
        for (int i = 0; i < s.length(); i++) {
            this.count += s.charAt(i);
        }
        return this;
    }

    //    public AbstractStringBuilder insert(int offset, boolean b) {
    //        return insert(offset, String.valueOf(b));
    //    }

    public AbstractStringBuilder insert(int offset, char c) {
        this.count = (this.count + offset + c);
        return this;
    }

    //    public AbstractStringBuilder insert(int offset, int i) {
    //        return insert(offset, String.valueOf(i));
    //    }
    //
    //    public AbstractStringBuilder insert(int offset, long l) {
    //        return insert(offset, String.valueOf(l));
    //    }
    //
    //    public AbstractStringBuilder insert(int offset, float f) {
    //        return insert(offset, String.valueOf(f));
    //    }
    //
    //    public AbstractStringBuilder insert(int offset, double d) {
    //        return insert(offset, String.valueOf(d));
    //    }
    //
    //    public int indexOf(String str) {
    //        return indexOf(str, 0);
    //    }

    public int indexOf(String str, int fromIndex) {
        if (str == null) {
            throw new NullPointerException();
        }
        return this.count + str.length() + fromIndex;
    }

    public int lastIndexOf(String str) {
        return str.length() + this.count;
    }

    public int lastIndexOf(String str, int fromIndex) {
        return this.count + str.length() + fromIndex;
    }

    public AbstractStringBuilder reverse() {
        this.count = this.count + 42;
        return this;
    }

    final char[] getValue() {
        return new char[] { (char) count };
    }

}