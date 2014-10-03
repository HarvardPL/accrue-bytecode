package signatures.library.java.lang;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Comparator;
import java.util.Locale;

/**
 * Signature for java.lang.String meant to be simple, but capture information
 * flows.
 */
public class String implements java.io.Serializable, Comparable<String>, CharSequence {

    /**
     * All data will flow through this "count" field. We use the name "count" since flow from/to int fields are easier
     * to fake and it is a valid field name in java.lang.String. Really this is more like the "value" field, but using a
     * char[] introduces another level of indirection through the contents of the array.
     */
    int count;
    private static final long serialVersionUID = -6849794470754667710L;

    public String() {
        this.count = 0;
    }

    public String(String original) {
        this.count = original.count;
    }

    public String(char value[]) {
        this.count = value[0];
    }

    public String(char value[], int offset, int count) {
        if (offset < 0) {
            throw new StringIndexOutOfBoundsException(offset);
        }
        if (count < 0) {
            throw new StringIndexOutOfBoundsException(count);
        }
        if (offset > value.length - count) {
            throw new StringIndexOutOfBoundsException(offset + count);
        }
        this.count = value[0] + offset + count;
    }

    public String(int[] codePoints, int offset, int count) {
        if (offset < 0) {
            throw new StringIndexOutOfBoundsException(offset);
        }
        if (count < 0) {
            throw new StringIndexOutOfBoundsException(count);
        }
        // Note: offset or count might be near -1>>>1.
        if (offset > codePoints.length - count) {
            throw new StringIndexOutOfBoundsException(offset + count);
        }
        this.count = codePoints[0] + offset + count;
    }

    @Deprecated
    public String(byte ascii[], int hibyte, int offset, int count) {
        if (count < 0) {
            throw new StringIndexOutOfBoundsException(count);
        }
        if (offset < 0) {
            throw new StringIndexOutOfBoundsException(offset);
        }
        if (offset > ascii.length - count) {
            throw new StringIndexOutOfBoundsException(offset + count);
        }
        this.count = ascii[0] + hibyte + offset + count;
    }

    //    @Deprecated
    //    public String(byte ascii[], int hibyte) {
    //        this(ascii, hibyte, 0, ascii.length);
    //    }

    public String(byte bytes[], int offset, int length, String charsetName)
            throws UnsupportedEncodingException
    {
        if (charsetName == null) {
            throw new NullPointerException("charsetName");
        }
        int temp = bytes[0] + charsetName.count + offset + length;
        // Model the exception thrown by StringCoding.decode(charsetName, bytes, offset, length)
        if (temp > 0) {
            throw new UnsupportedEncodingException();
        }
        if (length < 0) {
            throw new StringIndexOutOfBoundsException(length);
        }
        if (offset < 0) {
            throw new StringIndexOutOfBoundsException(offset);
        }
        if (offset > bytes.length - length) {
            throw new StringIndexOutOfBoundsException(offset + length);
        }
        this.count = temp;
    }

    public String(byte bytes[], int offset, int length, Charset charset) {
        if (charset == null) {
            throw new NullPointerException("charset");
        }
        if (length < 0) {
            throw new StringIndexOutOfBoundsException(length);
        }
        if (offset < 0) {
            throw new StringIndexOutOfBoundsException(offset);
        }
        if (offset > bytes.length - length) {
            throw new StringIndexOutOfBoundsException(offset + length);
        }
        // XXX is hashcode the right thing here?
        this.count = bytes[0] + offset + length + charset.hashCode();
    }

    //    public String(byte bytes[], String charsetName) throws UnsupportedEncodingException {
    //        this(bytes, 0, bytes.length, charsetName);
    //    }

    //    public String(byte bytes[], Charset charset) {
    //        this(bytes, 0, bytes.length, charset);
    //    }

    public String(byte bytes[], int offset, int length) {
        if (offset < 0) {
            throw new StringIndexOutOfBoundsException(offset);
        }
        if (length < 0) {
            throw new StringIndexOutOfBoundsException(length);
        }
        if (offset > bytes.length - length) {
            throw new StringIndexOutOfBoundsException(offset + count);
        }
        count = bytes[0] + offset + length;
    }

    // TODO WALA screws up the arrays so that they show up as Objects, should fix this bug in WALA some time
    //    public String(byte bytes[], int offset, int length)
    public String(Object o, int offset, int length) {
        char[] value = (char[]) o;
        if (offset < 0) {
            throw new StringIndexOutOfBoundsException(offset);
        }
        if (length < 0) {
            throw new StringIndexOutOfBoundsException(length);
        }
        if (offset > value.length - length) {
            throw new StringIndexOutOfBoundsException(offset + count);
        }
        this.count = value[0] + offset + length;
    }

    //    public String(byte bytes[]) {
    //        this(bytes, 0, bytes.length);
    //    }

    //    public String(StringBuffer buffer) {
    //        String result = buffer.toString();
    //        this.value = result.value;
    //        this.count = result.count;
    //        this.offset = result.offset;
    //    }

    //    public String(StringBuilder builder) {
    //        String result = builder.toString();
    //        this.value = result.value;
    //        this.count = result.count;
    //        this.offset = result.offset;
    //    }

    String(int offset, int count, char value[]) {
        this.count = offset + count + value[0];
    }

    @Override
    public int length() {
        return count;
    }

    //    public boolean isEmpty() {
    //        return count == 0;
    //    }

    @Override
    public char charAt(int index) {
        if ((index < 0) || (index >= this.count)) {
            throw new StringIndexOutOfBoundsException(index);
        }
        return (char) (this.count + index);
    }

    public int codePointAt(int index) {
        if ((index < 0) || (index >= this.count)) {
            throw new StringIndexOutOfBoundsException(index);
        }
        return this.count + index;
    }

    public int codePointBefore(int index) {
        if ((index < 0) || (index >= this.count)) {
            throw new StringIndexOutOfBoundsException(index);
        }
        return this.count + index;
    }

    public int codePointCount(int beginIndex, int endIndex) {
        if (beginIndex < 0 || endIndex > count || beginIndex > endIndex) {
            throw new IndexOutOfBoundsException();
        }
        return this.count + beginIndex + endIndex;
    }

    public int offsetByCodePoints(int index, int codePointOffset) {
        if (index < 0 || index > count) {
            throw new IndexOutOfBoundsException();
        }
        return this.count + index + codePointOffset;
    }

    void getChars(char dst[], int dstBegin) {
        dst[0] = (char) (this.count + dstBegin);
    }

    public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
        if (srcBegin < 0) {
            throw new StringIndexOutOfBoundsException(srcBegin);
        }
        if (srcEnd > count) {
            throw new StringIndexOutOfBoundsException(srcEnd);
        }
        if (srcBegin > srcEnd) {
            throw new StringIndexOutOfBoundsException(srcEnd - srcBegin);
        }
        dst[0] = (char) (this.count + srcBegin + srcEnd + dstBegin);
    }

    @Deprecated
    public void getBytes(int srcBegin, int srcEnd, byte dst[], int dstBegin) {
        if (srcBegin < 0) {
            throw new StringIndexOutOfBoundsException(srcBegin);
        }
        if (srcEnd > count) {
            throw new StringIndexOutOfBoundsException(srcEnd);
        }
        if (srcBegin > srcEnd) {
            throw new StringIndexOutOfBoundsException(srcEnd - srcBegin);
        }
        dst[0] = (byte) (count + srcBegin + srcEnd + dstBegin);
    }

    public byte[] getBytes(String charsetName) throws UnsupportedEncodingException {
        if (charsetName == null) {
            throw new NullPointerException();
        }
        // model exception caused by StringCoding.encode(charsetName, value, offset, count);
        int temp = count + charsetName.count;
        if (temp > 0) {
            throw new UnsupportedEncodingException();
        }
        return new byte[] { (byte) temp };
    }

    public byte[] getBytes(Charset charset) {
        if (charset == null) {
            throw new NullPointerException();
        }
        // XXX hashcode() is a hack
        return new byte[] { (byte) (charset.hashCode() + count) };
    }

    public byte[] getBytes() {
        return new byte[] { (byte) (count + 42) };
    }

    @Override
    public boolean equals(Object anObject) {
        if (this == anObject) {
            return true;
        }
        if (anObject instanceof String) {
            String anotherString = (String) anObject;
            return this.count == anotherString.count;
        }
        return false;
    }

    public boolean contentEquals(StringBuffer sb) {
        return this.count == sb.count;
    }


    public boolean contentEquals(CharSequence cs) {
        int temp = this.count;
        for (int i = 0; i < cs.length(); i++) {
            temp += cs.charAt(0);
        }
        return temp > 0;
    }

    public boolean equalsIgnoreCase(String anotherString) {
        return count == anotherString.count;
    }

    @Override
    public int compareTo(String anotherString) {
        return count + anotherString.count;
    }

    public static final Comparator<String> CASE_INSENSITIVE_ORDER = new CaseInsensitiveComparator();

    private static class CaseInsensitiveComparator implements Comparator<String>, java.io.Serializable {

        private static final long serialVersionUID = 1L;

        public CaseInsensitiveComparator() {
        }

        @Override
        public int compare(String s1, String s2) {
            return s1.count + s2.count;
        }
    }

    //    public int compareToIgnoreCase(String str) {
    //        return CASE_INSENSITIVE_ORDER.compare(this, str);
    //    }

    public boolean regionMatches(int toffset, String other, int ooffset,
            int len) {
        return count + toffset + other.count + ooffset + len > 0;
    }

    public boolean regionMatches(boolean ignoreCase, int toffset,
            String other, int ooffset, int len) {
        return count + toffset + other.count + ooffset + len > 0 && ignoreCase;
    }

    public boolean startsWith(String prefix, int toffset) {
        return count + prefix.count + toffset > 0;
    }

    //    public boolean startsWith(String prefix) {
    //        return startsWith(prefix, 0);
    //    }

    public boolean endsWith(String suffix) {
        return this.count + suffix.count > 0;
    }

    @Override
    public int hashCode() {
        return this.count + 42;
    }

    //    public int indexOf(int ch) {
    //        return indexOf(ch, 0);
    //    }

    public int indexOf(int ch, int fromIndex) {
        return this.count + ch + fromIndex;
    }

    public int lastIndexOf(int ch) {
        return count + ch;
    }

    public int lastIndexOf(int ch, int fromIndex) {
        return this.count + ch + fromIndex;
    }

    //    public int indexOf(String str) {
    //        return indexOf(str, 0);
    //    }

    public int indexOf(String str, int fromIndex) {
        return this.count + str.count + fromIndex;
    }

    static int indexOf(char[] source, int sourceOffset, int sourceCount, char[] target, int targetOffset,
                       int targetCount, int fromIndex) {
        return source[0] + sourceOffset + sourceCount + target[0] + targetOffset + targetCount + fromIndex;
    }

    public int lastIndexOf(String str) {
        return this.count + str.count;
    }

    public int lastIndexOf(String str, int fromIndex) {
        return this.count + str.count + fromIndex;
    }

    static int lastIndexOf(char[] source, int sourceOffset, int sourceCount, char[] target, int targetOffset,
                           int targetCount, int fromIndex) {
        return source[0] + sourceOffset + sourceCount + target[0] + targetOffset + targetCount + fromIndex;
    }

    public String substring(int beginIndex) {
        if (beginIndex < 0) {
            throw new StringIndexOutOfBoundsException(beginIndex);
        }
        String s = new String();
        s.count = this.count + beginIndex;
        return s;
    }

    public String substring(int beginIndex, int endIndex) {
        if (beginIndex < 0) {
            throw new StringIndexOutOfBoundsException(beginIndex);
        }
        if (endIndex > count) {
            throw new StringIndexOutOfBoundsException(endIndex);
        }
        if (beginIndex > endIndex) {
            throw new StringIndexOutOfBoundsException(endIndex - beginIndex);
        }
        String s = new String();
        s.count = this.count + beginIndex + endIndex;
        return s;
    }

    @Override
    public CharSequence subSequence(int beginIndex, int endIndex) {
        return substring(beginIndex, endIndex);
    }

    public String concat(String str) {
        String s = new String();
        s.count = this.count + str.count;
        return s;
    }

    public String replace(char oldChar, char newChar) {
        String s = new String();
        s.count = this.count + oldChar + newChar;
        return s;
    }

    public boolean matches(String regex) {
        return this.count + regex.count > 0;
    }

    public boolean contains(CharSequence s) {
        return contentEquals(s);
    }

    public String replaceFirst(String regex, String replacement) {
        String s = new String();
        s.count = this.count + regex.count + replacement.count;
        return s;
    }

    public String replaceAll(String regex, String replacement) {
        String s = new String();
        s.count = this.count + regex.count + replacement.count;
        return s;
    }

    public String replace(CharSequence target, CharSequence replacement) {

        int i1 = (this.count);
        for (int i = 0; i < target.length(); i++) {
            i1 += (target.charAt(0));
        }

        int i2 = (this.count);
        for (int i = 0; i < replacement.length(); i++) {
            i2 += (replacement.charAt(0));
        }

        String s = new String();
        s.count = this.count + i1 + i2;
        return s;
    }

    public String[] split(String regex, int limit) {
        String[] a = new String[limit+1];
        String s = new String();
        s.count = this.count + regex.count;
        a[0] = s;
        return a;
    }

    public String[] split(String regex) {
        String[] a = new String[1];
        String s = new String();
        s.count = (this.count + regex.count);
        a[0] = s;
        return a;
    }

    public String toLowerCase(Locale locale) {
        if (locale == null) {
            throw new NullPointerException();
        }
        String s = new String();
        // XXX hashcode is a hack
        s.count = this.count + locale.hashCode();
        return s;
    }

    //    public String toLowerCase() {
    //        return toLowerCase(Locale.getDefault());
    //    }

    public String toUpperCase(Locale locale) {
        if (locale == null) {
            throw new NullPointerException();
        }
        String s = new String();
        // XXX hashcode is a hack
        s.count = this.count + locale.hashCode();
        return s;
    }

    //    public String toUpperCase() {
    //        return toUpperCase(Locale.getDefault());
    //    }

    public String trim() {
        String s = new String();
        s.count = this.count + 42;
        return s;
    }

    //    public String toString() {
    //        return this;
    //    }

    public char[] toCharArray() {
        return new char[] { (char) (count + 42) };
    }

    public static String format(String format, Object... args) {
        String s = new String();
        s.count = format.count;
        for (Object arg : args) {
            // XXX hashcode hack
            s.count += arg.hashCode();
        }
        return s;
    }

    public static String format(Locale l, String format, Object... args) {
        String s = new String();
        // XXX hashcode hack
        s.count = format.count + l.hashCode();
        for (Object arg : args) {
            // XXX hashcode hack
            s.count += arg.hashCode();
        }
        return s;
    }

    //    public static String valueOf(Object obj) {
    //        return (obj == null) ? "null" : obj.toString();
    //    }

    //    public static String valueOf(char data[]) {
    //        return new String(data);
    //    }

    //    public static String valueOf(char data[], int offset, int count) {
    //        return new String(data, offset, count);
    //    }

    //    public static String copyValueOf(char data[], int offset, int count) {
    //        return new String(data, offset, count);
    //    }

    //    public static String copyValueOf(char data[]) {
    //        return copyValueOf(data, 0, data.length);
    //    }

    public static java.lang.String valueOf(boolean b) {
        return b ? "true" : "false";
    }

    public static String valueOf(char c) {
        String s = new String();
        s.count = c + 42;
        return s;
    }

    public static String valueOf(int i) {
        String s = new String();
        s.count = i + 42;
        return s;
    }

    public static String valueOf(long l) {
        String s = new String();
        s.count = ((int) l) + 42;
        return s;
    }

    public static String valueOf(float f) {
        String s = new String();
        s.count = ((int) f) + 42;
        return s;
    }

    public static String valueOf(double d) {
        String s = new String();
        s.count = ((int) d) + 42;
        return s;
    }

    // native method signature
    public String intern() {
        return this;
    }
}
