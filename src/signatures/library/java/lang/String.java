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
    private int count;
    private static final long serialVersionUID = -6849794470754667710L;

    static class StringCheats {
        static native int intFunctionOf(char[] a);

        static native int intFunctionOf(char[] a, int x, int y);

        static native int intFunctionOf(char[] a, int x, int y, int z);

        static native int intFunctionOf(char[] a, int x, int y, int z, int w);

        static native int intFunctionOf(char[] a, char[] b, int x, int y, int z, int w, int u);

        static native int intFunctionOf(byte[] a, int x, int y);

        static native int intFunctionOf(byte[] a, int x, int y, int z);

        static native int intFunctionOf(byte[] a, int x, int y, String z);

        static native int intFunctionOf(byte[] a, int x, int y, Charset z);

        static native int intFunctionOf(StringBuffer a);

        static native int intFunctionOf(StringBuilder a);

        static native int intFunctionOf(int x, CharSequence a, CharSequence b);

        static native int intFunctionOf(int x, int y, char[] a);

        static native int intFunctionOf(int x);

        static native int intFunctionOf(int x, int y);

        static native int intFunctionOf(int x, int y, int z);

        static native int intFunctionOf(int x, int y, int z, int w);

        static native int intFunctionOf(int x, Object y);

        static native int intFunctionOf(int x, Object z, Object w);

        static native int intFunctionOf(int x, int y, Object z, Object w);

        static native int intFunctionOf(int[] x, int y, int z);

        static native int intFunctionOf(Object o, int offset, int length);

        static native boolean booleanFunctionOf(byte[] a, int x, int y, int z);

        static native boolean booleanFunctionOf(int x);

        static native boolean booleanFunctionOf(int x, int y);

        static native boolean booleanFunctionOf(int x, int y, int z);

        static native boolean booleanFunctionOf(int x, int y, int z, int w);

        static native boolean booleanFunctionOf(int x, int y, int z, int w, int u);

        static native boolean booleanFunctionOf(int x, int y, int z, int w, int u, boolean b);

        static native boolean booleanFunctionOf(int x, Object y);

        static native char charFunctionOf(int x, int y);

        static native char[] charArrayFunctionOf(int x);

        static native byte[] byteArrayFunctionOf(int x, int y, int z, byte[] a, int w);

        static native byte[] byteArrayFunctionOf(int x, int y);

        static native byte[] byteArrayFunctionOf(int x);

        static native byte[] byteArrayFunctionOf(int x, Object y);
    }

    public String() {
        count = 0;
    }

    //    public String(int data) {
    //        this.data = data;
    //    }
    //
    //    public String(long data) {
    //        this.data = (int)data;
    //    }

    public String(String original) {
        count = original.count;
    }

    public String(char value[]) {
        count = StringCheats.intFunctionOf(value);
    }

    public String(char value[], int offset, int count) {
        count = StringCheats.intFunctionOf(value, offset, count);
    }

    public String(int[] codePoints, int offset, int count) {
        count = StringCheats.intFunctionOf(codePoints, offset, count);
    }

    @Deprecated
    public String(byte ascii[], int hibyte, int offset, int count) {
        count = StringCheats.intFunctionOf(ascii, hibyte, offset, count);
    }

    @Deprecated
    public String(byte ascii[], int hibyte) {
        this(ascii, hibyte, 0, ascii.length);
    }

    public String(byte bytes[], int offset, int length, String charsetName)
            throws UnsupportedEncodingException
    {
        if (charsetName == null) {
            throw new NullPointerException("charsetName");
        }
        if (StringCheats.booleanFunctionOf(bytes, charsetName.count, offset, length)) {
            throw new UnsupportedEncodingException();
        }
        count = StringCheats.intFunctionOf(bytes, charsetName.count, offset, length);
    }

    public String(byte bytes[], int offset, int length, Charset charset) {
        if (charset == null) {
            throw new NullPointerException("charset");
        }
        count = StringCheats.intFunctionOf(bytes, offset, length, charset);
    }

    public String(byte bytes[], String charsetName)
            throws UnsupportedEncodingException
    {
        this(bytes, 0, bytes.length, charsetName);
    }

    public String(byte bytes[], Charset charset) {
        this(bytes, 0, bytes.length, charset);
    }

    public String(byte bytes[], int offset, int length) {
        count = StringCheats.intFunctionOf(bytes, offset, length);
    }

    // TODO WALA screws up the arrays so that they show up as Objects, should fix this bug some time
    public String(Object o, int offset, int length) {
        count = StringCheats.intFunctionOf(o, offset, length);
    }

    public String(byte bytes[]) {
        this(bytes, 0, bytes.length);
    }

    public String(StringBuffer buffer) {
        count = StringCheats.intFunctionOf(buffer);
    }

    public String(StringBuilder builder) {
        count = StringCheats.intFunctionOf(builder);
    }

    String(int offset, int count, char value[]) {
        count = StringCheats.intFunctionOf(offset, count, value);
    }

    @Override
    public int length() {
        return count;
    }

    public boolean isEmpty() {
        return StringCheats.booleanFunctionOf(count);
    }

    @Override
    public char charAt(int index) {
        return StringCheats.charFunctionOf(count, index);
    }

    public int codePointAt(int index) {
        return StringCheats.intFunctionOf(count, index);
    }

    public int codePointBefore(int index) {
        return StringCheats.intFunctionOf(count, index);
    }

    public int codePointCount(int beginIndex, int endIndex) {
        return StringCheats.intFunctionOf(count, beginIndex, endIndex);
    }

    public int offsetByCodePoints(int index, int codePointOffset) {
        return StringCheats.intFunctionOf(count, index, codePointOffset);
    }

    void getChars(char dst[], int dstBegin) {
        dst[0] = (char) StringCheats.intFunctionOf(count, dstBegin);
    }

    public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
        dst[0] = (char) StringCheats.intFunctionOf(count, srcBegin, srcEnd, dstBegin);
    }

    void copyChars(int srcBegin, int len, char dst[], int dstBegin) {
        dst[0] = (char) StringCheats.intFunctionOf(count, srcBegin, len, dstBegin);
    }

    int copyBytes(int srcBegin, int len, byte dst[], int dstBegin) {
        dst[0] = (byte) StringCheats.intFunctionOf(count, srcBegin, len, dstBegin);
        return StringCheats.intFunctionOf(count, srcBegin, len, dstBegin);
    }

    @Deprecated
    public void getBytes(int srcBegin, int srcEnd, byte dst[], int dstBegin) {
        dst = StringCheats.byteArrayFunctionOf(count, srcBegin, srcEnd, dst, dstBegin);
    }

    public byte[] getBytes(String charsetName) throws UnsupportedEncodingException {
        if (charsetName == null) {
            throw new NullPointerException();
        }
        if (StringCheats.booleanFunctionOf(count, charsetName.count)) {
            throw new UnsupportedEncodingException();
        }
        return StringCheats.byteArrayFunctionOf(count, charsetName.count);
    }

    public byte[] getBytes(Charset charset) {
        if (charset == null) {
            throw new NullPointerException();
        }
        return StringCheats.byteArrayFunctionOf(count, charset);
    }

    public byte[] getBytes() {
        return StringCheats.byteArrayFunctionOf(count);
    }

    @Override
    public boolean equals(Object anObject) {
        return StringCheats.booleanFunctionOf(count, anObject);
    }

    // TODO synchronized was removed
    public boolean contentEquals(StringBuffer sb) {
        return contentEquals((CharSequence) sb);
    }

    public boolean contentEquals(CharSequence cs) {
        return StringCheats.booleanFunctionOf(count, cs);
    }

    public boolean equalsIgnoreCase(String anotherString) {
        return StringCheats.booleanFunctionOf(count, anotherString.count);
    }

    @Override
    public int compareTo(String anotherString) {
        return StringCheats.intFunctionOf(count, anotherString.count);
    }

    public static final Comparator<String> CASE_INSENSITIVE_ORDER = new CaseInsensitiveComparator();

    private static class CaseInsensitiveComparator implements Comparator<String>, java.io.Serializable {

        private static final long serialVersionUID = 1L;

        public CaseInsensitiveComparator() {
        }

        @Override
        public int compare(String s1, String s2) {
            return StringCheats.intFunctionOf(s1.count, s2.count);
        }
    }

    public int compareToIgnoreCase(String str) {
        return CASE_INSENSITIVE_ORDER.compare(this, str);
    }

    public boolean regionMatches(int toffset, String other, int ooffset,
            int len) {
        return StringCheats.booleanFunctionOf(count, toffset, other.count, ooffset, len);
    }

    public boolean regionMatches(boolean ignoreCase, int toffset,
            String other, int ooffset, int len) {
        return StringCheats.booleanFunctionOf(count, toffset, other.count, ooffset, len, ignoreCase);
    }

    public boolean startsWith(String prefix, int toffset) {
        return StringCheats.booleanFunctionOf(count, prefix.count, toffset);
    }

    public boolean startsWith(String prefix) {
        return StringCheats.booleanFunctionOf(count, prefix.count);
    }

    public boolean endsWith(String suffix) {
        return StringCheats.booleanFunctionOf(count, suffix.count);
    }

    @Override
    public int hashCode() {
        return StringCheats.intFunctionOf(count);
    }

    public int indexOf(int ch) {
        return indexOf(ch, 0);
    }

    public int indexOf(int ch, int fromIndex) {
        return StringCheats.intFunctionOf(count, ch, fromIndex);
    }

    public int lastIndexOf(int ch) {
        return StringCheats.intFunctionOf(count, ch);
    }

    public int lastIndexOf(int ch, int fromIndex) {
        return StringCheats.intFunctionOf(count, ch, fromIndex);
    }

    public int indexOf(String str) {
        return StringCheats.intFunctionOf(count, str.count);
    }

    public int indexOf(String str, int fromIndex) {
        return StringCheats.intFunctionOf(count, str.count, fromIndex);
    }

    int indexOf(char[] source, int sourceOffset, int sourceCount,
            int fromIndex) {
        return StringCheats.intFunctionOf(source, sourceOffset, sourceCount, fromIndex, count);
    }

    static int indexOf(char[] source, int sourceOffset, int sourceCount,
            char[] target, int targetOffset, int targetCount,
            int fromIndex) {
        return StringCheats.intFunctionOf(source, target, sourceOffset, sourceCount, targetOffset, targetCount, fromIndex);
    }

    public int lastIndexOf(String str) {
        return StringCheats.intFunctionOf(count, str.count);
    }

    public int lastIndexOf(String str, int fromIndex) {
        return StringCheats.intFunctionOf(count, str.count, fromIndex);
    }

    int lastIndexOf(char[] source, int sourceOffset, int sourceCount,
            int fromIndex) {
        return StringCheats.intFunctionOf(source, sourceOffset, sourceCount, fromIndex, count);
    }

    static int lastIndexOf(char[] source, int sourceOffset, int sourceCount,
            char[] target, int targetOffset, int targetCount,
            int fromIndex) {
        return StringCheats.intFunctionOf(source, target, sourceOffset, sourceCount, targetOffset, targetCount, fromIndex);
    }

    public String substring(int beginIndex) {
        if (StringCheats.booleanFunctionOf(count, beginIndex)) {
            throw new StringIndexOutOfBoundsException(StringCheats.intFunctionOf(count, beginIndex));
        }
        String s = new String();
        s.count = StringCheats.intFunctionOf(count, beginIndex);
        return s;
    }

    public String substring(int beginIndex, int endIndex) {
        if (StringCheats.booleanFunctionOf(count, beginIndex, endIndex)) {
            throw new StringIndexOutOfBoundsException(StringCheats.intFunctionOf(count, beginIndex, endIndex));
        }
        String s = new String();
        s.count = StringCheats.intFunctionOf(count, beginIndex, endIndex);
        return s;
    }

    @Override
    public CharSequence subSequence(int beginIndex, int endIndex) {
        return substring(beginIndex, endIndex);
    }

    public String concat(String str) {
        String s = new String();
        s.count = StringCheats.intFunctionOf(count, str.count);
        return s;
    }

    public String replace(char oldChar, char newChar) {
        String s = new String();
        s.count = StringCheats.intFunctionOf(count, oldChar, newChar);
        return s;
    }

    public boolean matches(String regex) {
        return StringCheats.booleanFunctionOf(count, regex.count);
    }

    public boolean contains(CharSequence s) {
        return StringCheats.booleanFunctionOf(count, s);
    }

    public String replaceFirst(String regex, String replacement) {
        String s = new String();
        s.count = StringCheats.intFunctionOf(count, regex.count, replacement.count);
        return s;
    }

    public String replaceAll(String regex, String replacement) {
        String s = new String();
        s.count = StringCheats.intFunctionOf(count, regex.count, replacement.count);
        return s;
    }

    public String replace(CharSequence target, CharSequence replacement) {
        String s = new String();
        s.count = StringCheats.intFunctionOf(count, target, replacement);
        return s;
    }

    public String[] split(String regex, int limit) {
        String[] a = new String[limit+1];
        String s = new String();
        s.count = StringCheats.intFunctionOf(this.count, regex.count);
        a[0] = s;
        return a;
    }

    public String[] split(String regex) {
        String[] a = new String[1];
        String s = new String();
        s.count = StringCheats.intFunctionOf(this.count, regex.count);
        a[0] = s;
        return a;
    }

    public String toLowerCase(Locale locale) {
        String s = new String();
        s.count = StringCheats.intFunctionOf(this.count, locale);
        return s;
    }

    public String toLowerCase() {
        String s = new String();
        s.count = StringCheats.intFunctionOf(this.count);
        return s;
    }

    public String toUpperCase(Locale locale) {
        String s = new String();
        s.count = StringCheats.intFunctionOf(this.count, locale);
        return s;
    }

    public String toUpperCase() {
        String s = new String();
        s.count = StringCheats.intFunctionOf(this.count);
        return s;
    }

    public String trim() {
        String s = new String();
        s.count = StringCheats.intFunctionOf(this.count);
        return s;
    }

    // No need for a signature. The real method is exactly what we want.
    //public String toString() {return this;}

    public char[] toCharArray() {
        return StringCheats.charArrayFunctionOf(count);
    }

    public static String format(String format, Object... args) {
        String s = new String();
        s.count = StringCheats.intFunctionOf(format.count, args);
        return s;
    }

    public static String format(Locale l, String format, Object... args) {
        String s = new String();
        s.count = StringCheats.intFunctionOf(format.count, l, args);
        return s;
    }

    public static java.lang.String valueOf(Object obj) {
        return (obj == null) ? "null" : obj.toString();
    }

    public static String valueOf(char data[]) {
        return new String(data);
    }

    public static String valueOf(char data[], int offset, int count) {
        return new String(data, offset, count);
    }

    public static String copyValueOf(char data[], int offset, int count) {
        return new String(data, offset, count);
    }

    public static String copyValueOf(char data[]) {
        return copyValueOf(data, 0, data.length);
    }

    public static java.lang.String valueOf(boolean b) {
        return b ? "true" : "false";
    }

    public static String valueOf(char c) {
        char data[] = { c };
        return new String(0, 1, data);
    }

    public static String valueOf(int i) {
        String s = new String();
        s.count = i;
        return s;
    }

    public static String valueOf(long l) {
        String s = new String();
        s.count = (int) l;
        return s;
    }

    public static String valueOf(float f) {
        String s = new String();
        s.count = (int) f;
        return s;
    }

    public static String valueOf(double d) {
        String s = new String();
        s.count = (int) d;
        return s;
    }

    public String intern() {
        return this;
    }
}
