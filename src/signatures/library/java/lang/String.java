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

    public int data;
    private static final long serialVersionUID = -6849794470754667710L;

    static class StringCheats {
        static native int intFunctionOf(String a);

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
        data = 0;
    }

    //    public String(int data) {
    //        this.data = data;
    //    }
    //
    //    public String(long data) {
    //        this.data = (int)data;
    //    }

    public String(String original) {
        data = StringCheats.intFunctionOf(original);
    }

    public String(char value[]) {
        data = StringCheats.intFunctionOf(value);
    }

    public String(char value[], int offset, int count) {
        data = StringCheats.intFunctionOf(value, offset, count);
    }

    public String(int[] codePoints, int offset, int count) {
        data = StringCheats.intFunctionOf(codePoints, offset, count);
    }

    @Deprecated
    public String(byte ascii[], int hibyte, int offset, int count) {
        data = StringCheats.intFunctionOf(ascii, hibyte, offset, count);
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
        if (StringCheats.booleanFunctionOf(bytes, charsetName.data, offset, length)) {
            throw new UnsupportedEncodingException();
        }
        data = StringCheats.intFunctionOf(bytes, charsetName.data, offset, length);
    }

    public String(byte bytes[], int offset, int length, Charset charset) {
        if (charset == null) {
            throw new NullPointerException("charset");
        }
        data = StringCheats.intFunctionOf(bytes, offset, length, charset);
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
        data = StringCheats.intFunctionOf(bytes, offset, length);
    }

    // TODO WALA screws up the arrays so that they show up as Objects, should fix this bug some time
    public String(Object o, int offset, int length) {
        data = StringCheats.intFunctionOf(o, offset, length);
    }

    public String(byte bytes[]) {
        this(bytes, 0, bytes.length);
    }

    public String(StringBuffer buffer) {
        data = StringCheats.intFunctionOf(buffer);
    }

    public String(StringBuilder builder) {
        data = StringCheats.intFunctionOf(builder);
    }

    String(int offset, int count, char value[]) {
        data = StringCheats.intFunctionOf(offset, count, value);
    }

    @Override
    public int length() {
        return data;
    }

    public boolean isEmpty() {
        return StringCheats.booleanFunctionOf(data);
    }

    @Override
    public char charAt(int index) {
        return StringCheats.charFunctionOf(data, index);
    }

    public int codePointAt(int index) {
        return StringCheats.intFunctionOf(data, index);
    }

    public int codePointBefore(int index) {
        return StringCheats.intFunctionOf(data, index);
    }

    public int codePointCount(int beginIndex, int endIndex) {
        return StringCheats.intFunctionOf(data, beginIndex, endIndex);
    }

    public int offsetByCodePoints(int index, int codePointOffset) {
        return StringCheats.intFunctionOf(data, index, codePointOffset);
    }

    void getChars(char dst[], int dstBegin) {
        dst[0] = (char)StringCheats.intFunctionOf(data, dstBegin);
    }

    public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
        dst[0] = (char)StringCheats.intFunctionOf(data, srcBegin, srcEnd, dstBegin);
    }

    void copyChars(int srcBegin, int len, char dst[], int dstBegin) {
        dst[0] = (char)StringCheats.intFunctionOf(data, srcBegin, len, dstBegin);
    }

    int copyBytes(int srcBegin, int len, byte dst[], int dstBegin) {
        dst[0] = (byte)StringCheats.intFunctionOf(data, srcBegin, len, dstBegin);
        return StringCheats.intFunctionOf(data, srcBegin, len, dstBegin);
    }

    @Deprecated
    public void getBytes(int srcBegin, int srcEnd, byte dst[], int dstBegin) {
        dst = StringCheats.byteArrayFunctionOf(data, srcBegin, srcEnd, dst, dstBegin);
    }

    public byte[] getBytes(String charsetName) throws UnsupportedEncodingException {
        if (charsetName == null) {
            throw new NullPointerException();
        }
        if (StringCheats.booleanFunctionOf(data, charsetName.data)) {
            throw new UnsupportedEncodingException();
        }
        return StringCheats.byteArrayFunctionOf(data, charsetName.data);
    }

    public byte[] getBytes(Charset charset) {
        if (charset == null) {
            throw new NullPointerException();
        }
        return StringCheats.byteArrayFunctionOf(data, charset);
    }

    public byte[] getBytes() {
        return StringCheats.byteArrayFunctionOf(data);
    }

    @Override
    public boolean equals(Object anObject) {
        return StringCheats.booleanFunctionOf(data, anObject);
    }

    // TODO synchronized was removed
    public boolean contentEquals(StringBuffer sb) {
        return contentEquals((CharSequence) sb);
    }

    public boolean contentEquals(CharSequence cs) {
        return StringCheats.booleanFunctionOf(data, cs);
    }

    public boolean equalsIgnoreCase(String anotherString) {
        return StringCheats.booleanFunctionOf(data, anotherString.data);
    }

    @Override
    public int compareTo(String anotherString) {
        return StringCheats.intFunctionOf(data, anotherString.data);
    }

    public static final Comparator<String> CASE_INSENSITIVE_ORDER = new CaseInsensitiveComparator();

    private static class CaseInsensitiveComparator implements Comparator<String>, java.io.Serializable {

        private static final long serialVersionUID = 1L;

        public CaseInsensitiveComparator() {
        }

        @Override
        public int compare(String s1, String s2) {
            return StringCheats.intFunctionOf(s1.data, s2.data);
        }
    }

    public int compareToIgnoreCase(String str) {
        return CASE_INSENSITIVE_ORDER.compare(this, str);
    }

    public boolean regionMatches(int toffset, String other, int ooffset,
            int len) {
        return StringCheats.booleanFunctionOf(data, toffset, other.data, ooffset, len);
    }

    public boolean regionMatches(boolean ignoreCase, int toffset,
            String other, int ooffset, int len) {
        return StringCheats.booleanFunctionOf(data, toffset, other.data, ooffset, len, ignoreCase);
    }

    public boolean startsWith(String prefix, int toffset) {
        return StringCheats.booleanFunctionOf(data, prefix.data, toffset);
    }

    public boolean startsWith(String prefix) {
        return StringCheats.booleanFunctionOf(data, prefix.data);
    }

    public boolean endsWith(String suffix) {
        return StringCheats.booleanFunctionOf(data, suffix.data);
    }

    @Override
    public int hashCode() {
        return StringCheats.intFunctionOf(data);
    }

    public int indexOf(int ch) {
        return indexOf(ch, 0);
    }

    public int indexOf(int ch, int fromIndex) {
        return StringCheats.intFunctionOf(data, ch, fromIndex);
    }

    public int lastIndexOf(int ch) {
        return StringCheats.intFunctionOf(data, ch);
    }

    public int lastIndexOf(int ch, int fromIndex) {
        return StringCheats.intFunctionOf(data, ch, fromIndex);
    }

    public int indexOf(String str) {
        return StringCheats.intFunctionOf(data, str.data);
    }

    public int indexOf(String str, int fromIndex) {
        return StringCheats.intFunctionOf(data, str.data, fromIndex);
    }

    int indexOf(char[] source, int sourceOffset, int sourceCount,
            int fromIndex) {
        return StringCheats.intFunctionOf(source, sourceOffset, sourceCount, fromIndex, data);
    }

    static int indexOf(char[] source, int sourceOffset, int sourceCount,
            char[] target, int targetOffset, int targetCount,
            int fromIndex) {
        return StringCheats.intFunctionOf(source, target, sourceOffset, sourceCount, targetOffset, targetCount, fromIndex);
    }

    public int lastIndexOf(String str) {
        return StringCheats.intFunctionOf(data, str.data);
    }

    public int lastIndexOf(String str, int fromIndex) {
        return StringCheats.intFunctionOf(data, str.data, fromIndex);
    }

    int lastIndexOf(char[] source, int sourceOffset, int sourceCount,
            int fromIndex) {
        return StringCheats.intFunctionOf(source, sourceOffset, sourceCount, fromIndex, data);
    }

    static int lastIndexOf(char[] source, int sourceOffset, int sourceCount,
            char[] target, int targetOffset, int targetCount,
            int fromIndex) {
        return StringCheats.intFunctionOf(source, target, sourceOffset, sourceCount, targetOffset, targetCount, fromIndex);
    }

    public String substring(int beginIndex) {
        if (StringCheats.booleanFunctionOf(data, beginIndex)) {
            throw new StringIndexOutOfBoundsException(StringCheats.intFunctionOf(data, beginIndex));
        }
        String s = new String();
        s.data = StringCheats.intFunctionOf(data, beginIndex);
        return s;
    }

    public String substring(int beginIndex, int endIndex) {
        if (StringCheats.booleanFunctionOf(data, beginIndex, endIndex)) {
            throw new StringIndexOutOfBoundsException(StringCheats.intFunctionOf(data, beginIndex, endIndex));
        }
        String s = new String();
        s.data = StringCheats.intFunctionOf(data, beginIndex, endIndex);
        return s;
    }

    @Override
    public CharSequence subSequence(int beginIndex, int endIndex) {
        return substring(beginIndex, endIndex);
    }

    public String concat(String str) {
        String s = new String();
        s.data = StringCheats.intFunctionOf(data, str.data);
        return s;
    }

    public String replace(char oldChar, char newChar) {
        String s = new String();
        s.data = StringCheats.intFunctionOf(data, oldChar, newChar);
        return s;
    }

    public boolean matches(String regex) {
        return StringCheats.booleanFunctionOf(data, regex.data);
    }

    public boolean contains(CharSequence s) {
        return StringCheats.booleanFunctionOf(data, s);
    }

    public String replaceFirst(String regex, String replacement) {
        String s = new String();
        s.data = StringCheats.intFunctionOf(data, regex.data, replacement.data);
        return s;
    }

    public String replaceAll(String regex, String replacement) {
        String s = new String();
        s.data = StringCheats.intFunctionOf(data, regex.data, replacement.data);
        return s;
    }

    public String replace(CharSequence target, CharSequence replacement) {
        String s = new String();
        s.data = StringCheats.intFunctionOf(data, target, replacement);
        return s;
    }

    public String[] split(String regex, int limit) {
        String[] a = new String[limit+1];
        String s = new String();
        s.data = StringCheats.intFunctionOf(this.data, regex.data);
        a[0] = s;
        return a;
    }

    public String[] split(String regex) {
        String[] a = new String[1];
        String s = new String();
        s.data = StringCheats.intFunctionOf(this.data, regex.data);
        a[0] = s;
        return a;
    }

    public String toLowerCase(Locale locale) {
        String s = new String();
        s.data = StringCheats.intFunctionOf(this.data, locale);
        return s;
    }

    public String toLowerCase() {
        String s = new String();
        s.data = StringCheats.intFunctionOf(this.data);
        return s;
    }

    public String toUpperCase(Locale locale) {
        String s = new String();
        s.data = StringCheats.intFunctionOf(this.data, locale);
        return s;
    }

    public String toUpperCase() {
        String s = new String();
        s.data = StringCheats.intFunctionOf(this.data);
        return s;
    }

    public String trim() {
        String s = new String();
        s.data = StringCheats.intFunctionOf(this.data);
        return s;
    }

    // No need for a signature. The real method is exactly what we want.
    //public String toString() {return this;}

    public char[] toCharArray() {
        return StringCheats.charArrayFunctionOf(data);
    }

    public static String format(String format, Object... args) {
        String s = new String();
        s.data = StringCheats.intFunctionOf(format.data, args);
        return s;
    }

    public static String format(Locale l, String format, Object... args) {
        String s = new String();
        s.data = StringCheats.intFunctionOf(format.data, l, args);
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
        s.data = i;
        return s;
    }

    public static String valueOf(long l) {
        String s = new String();
        s.data = (int) l;
        return s;
    }

    public static String valueOf(float f) {
        String s = new String();
        s.data = (int) f;
        return s;
    }

    public static String valueOf(double d) {
        String s = new String();
        s.data = (int) d;
        return s;
    }

    public String intern() {
        return this;
    }
}
