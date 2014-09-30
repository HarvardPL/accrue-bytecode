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
        count = 0;
    }

    public String(String original) {
        count = original.count;
    }

    public String(char value[]) {
        count = MockNativeMethods.intFunctionOf(value[0]);
    }

    public String(char value[], int offset, int count) {
        count = MockNativeMethods.intFunctionOf(value[0], offset, count);
    }

    public String(int[] codePoints, int offset, int count) {
        count = MockNativeMethods.intFunctionOf(codePoints[0], offset, count);
    }

    @Deprecated
    public String(byte ascii[], int hibyte, int offset, int count) {
        count = MockNativeMethods.intFunctionOf(ascii[0], hibyte, offset, count);
    }

    @Deprecated
    public String(byte ascii[], int hibyte) {
        this(ascii, hibyte, 0, ascii[0]);
    }

    public String(byte bytes[], int offset, int length, String charsetName)
            throws UnsupportedEncodingException
    {
        if (charsetName == null) {
            throw new NullPointerException("charsetName");
        }
        if (MockNativeMethods.booleanFunctionOf(bytes[0], charsetName.count, offset, length)) {
            throw new UnsupportedEncodingException();
        }
        count = MockNativeMethods.intFunctionOf(bytes[0], charsetName.count, offset, length);
    }

    public String(byte bytes[], int offset, int length, Charset charset) {
        if (charset == null) {
            throw new NullPointerException("charset");
        }
        count = MockNativeMethods.intFunctionOf(bytes[0], offset, length, charset);
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
        count = MockNativeMethods.intFunctionOf(bytes[0], offset, length);
    }

    // TODO WALA screws up the arrays so that they show up as Objects, should fix this bug some time
    public String(Object o, int offset, int length) {
        count = MockNativeMethods.intFunctionOf(valueOf(o).count, offset, length);
    }

    public String(byte bytes[]) {
        this(bytes, 0, bytes.length);
    }

    public String(StringBuffer buffer) {
        count = MockNativeMethods.intFunctionOf(valueOf(buffer.toString()).count);
    }

    public String(StringBuilder builder) {
        count = MockNativeMethods.intFunctionOf(valueOf(builder.toString()).count);
    }

    String(int offset, int count, char value[]) {
        count = MockNativeMethods.intFunctionOf(offset, count, value[0]);
    }

    @Override
    public int length() {
        return MockNativeMethods.intFunctionOf(count);
    }

    public boolean isEmpty() {
        return MockNativeMethods.booleanFunctionOf(count);
    }

    @Override
    public char charAt(int index) {
        return (char) MockNativeMethods.intFunctionOf(count, index);
    }

    public int codePointAt(int index) {
        return MockNativeMethods.intFunctionOf(count, index);
    }

    public int codePointBefore(int index) {
        return MockNativeMethods.intFunctionOf(count, index);
    }

    public int codePointCount(int beginIndex, int endIndex) {
        return MockNativeMethods.intFunctionOf(count, beginIndex, endIndex);
    }

    public int offsetByCodePoints(int index, int codePointOffset) {
        return MockNativeMethods.intFunctionOf(count, index, codePointOffset);
    }

    void getChars(char dst[], int dstBegin) {
        dst[0] = (char) MockNativeMethods.intFunctionOf(count, dstBegin);
    }

    public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
        dst[0] = (char) MockNativeMethods.intFunctionOf(count, srcBegin, srcEnd, dstBegin);
    }

    void copyChars(int srcBegin, int len, char dst[], int dstBegin) {
        dst[0] = (char) MockNativeMethods.intFunctionOf(count, srcBegin, len, dstBegin);
    }

    int copyBytes(int srcBegin, int len, byte dst[], int dstBegin) {
        dst[0] = (byte) MockNativeMethods.intFunctionOf(count, srcBegin, len, dstBegin);
        return MockNativeMethods.intFunctionOf(count, srcBegin, len, dstBegin);
    }

    @Deprecated
    public void getBytes(int srcBegin, int srcEnd, byte dst[], int dstBegin) {
        dst[0] = (byte) MockNativeMethods.intFunctionOf(count, srcBegin, srcEnd, dstBegin);
    }

    public byte[] getBytes(String charsetName) throws UnsupportedEncodingException {
        if (charsetName == null) {
            throw new NullPointerException();
        }
        if (MockNativeMethods.booleanFunctionOf(count, charsetName.count)) {
            throw new UnsupportedEncodingException();
        }
        return new byte[] { (byte) MockNativeMethods.intFunctionOf(count, charsetName.count) };
    }

    public byte[] getBytes(Charset charset) {
        if (charset == null) {
            throw new NullPointerException();
        }
        return new byte[] { (byte) MockNativeMethods.intFunctionOf(count, valueOf(charset).count) };
    }

    public byte[] getBytes() {
        return new byte[] { (byte) MockNativeMethods.intFunctionOf(count) };
    }

    @Override
    public boolean equals(Object anObject) {
        return MockNativeMethods.booleanFunctionOf(count, valueOf(anObject).count);
    }

    public boolean contentEquals(StringBuffer sb) {
        return contentEquals((CharSequence) sb);
    }

    public boolean contentEquals(CharSequence cs) {
        return MockNativeMethods.booleanFunctionOf(count, valueOf(cs).count);
    }

    public boolean equalsIgnoreCase(String anotherString) {
        return MockNativeMethods.booleanFunctionOf(count, anotherString.count);
    }

    @Override
    public int compareTo(String anotherString) {
        return MockNativeMethods.intFunctionOf(count, anotherString.count);
    }

    public static final Comparator<String> CASE_INSENSITIVE_ORDER = new CaseInsensitiveComparator();

    private static class CaseInsensitiveComparator implements Comparator<String>, java.io.Serializable {

        private static final long serialVersionUID = 1L;

        public CaseInsensitiveComparator() {
        }

        @Override
        public int compare(String s1, String s2) {
            return MockNativeMethods.intFunctionOf(s1.count, s2.count);
        }
    }

    public int compareToIgnoreCase(String str) {
        return CASE_INSENSITIVE_ORDER.compare(this, str);
    }

    public boolean regionMatches(int toffset, String other, int ooffset,
            int len) {
        return MockNativeMethods.booleanFunctionOf(count, toffset, other.count, ooffset, len);
    }

    public boolean regionMatches(boolean ignoreCase, int toffset,
            String other, int ooffset, int len) {
        return MockNativeMethods.booleanFunctionOf(count, toffset, other.count, ooffset, len, ignoreCase);
    }

    public boolean startsWith(String prefix, int toffset) {
        return MockNativeMethods.booleanFunctionOf(count, prefix.count, toffset);
    }

    public boolean startsWith(String prefix) {
        return MockNativeMethods.booleanFunctionOf(count, prefix.count);
    }

    public boolean endsWith(String suffix) {
        return MockNativeMethods.booleanFunctionOf(count, suffix.count);
    }

    @Override
    public int hashCode() {
        return MockNativeMethods.intFunctionOf(count);
    }

    public int indexOf(int ch) {
        return indexOf(ch, 0);
    }

    public int indexOf(int ch, int fromIndex) {
        return MockNativeMethods.intFunctionOf(count, ch, fromIndex);
    }

    public int lastIndexOf(int ch) {
        return MockNativeMethods.intFunctionOf(count, ch);
    }

    public int lastIndexOf(int ch, int fromIndex) {
        return MockNativeMethods.intFunctionOf(count, ch, fromIndex);
    }

    public int indexOf(String str) {
        return MockNativeMethods.intFunctionOf(count, str.count);
    }

    public int indexOf(String str, int fromIndex) {
        return MockNativeMethods.intFunctionOf(count, str.count, fromIndex);
    }

    int indexOf(char[] source, int sourceOffset, int sourceCount,
            int fromIndex) {
        return MockNativeMethods.intFunctionOf(source[0], sourceOffset, sourceCount, fromIndex, count);
    }

    public int lastIndexOf(String str) {
        return MockNativeMethods.intFunctionOf(count, str.count);
    }

    public int lastIndexOf(String str, int fromIndex) {
        return MockNativeMethods.intFunctionOf(count, str.count, fromIndex);
    }

    int lastIndexOf(char[] source, int sourceOffset, int sourceCount,
            int fromIndex) {
        return MockNativeMethods.intFunctionOf(source[0], sourceOffset, sourceCount, fromIndex, count);
    }

    public String substring(int beginIndex) {
        if (MockNativeMethods.booleanFunctionOf(count, beginIndex)) {
            throw new StringIndexOutOfBoundsException(MockNativeMethods.intFunctionOf(count, beginIndex));
        }
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(count, beginIndex);
        return s;
    }

    public String substring(int beginIndex, int endIndex) {
        if (MockNativeMethods.booleanFunctionOf(count, beginIndex, endIndex)) {
            throw new StringIndexOutOfBoundsException(MockNativeMethods.intFunctionOf(count, beginIndex, endIndex));
        }
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(count, beginIndex, endIndex);
        return s;
    }

    @Override
    public CharSequence subSequence(int beginIndex, int endIndex) {
        return substring(beginIndex, endIndex);
    }

    public String concat(String str) {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(count, str.count);
        return s;
    }

    public String replace(char oldChar, char newChar) {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(count, oldChar, newChar);
        return s;
    }

    public boolean matches(String regex) {
        return MockNativeMethods.booleanFunctionOf(count, regex.count);
    }

    public boolean contains(CharSequence s) {
        return MockNativeMethods.booleanFunctionOf(count, valueOf(s).count);
    }

    public String replaceFirst(String regex, String replacement) {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(count, regex.count, replacement.count);
        return s;
    }

    public String replaceAll(String regex, String replacement) {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(count, regex.count, replacement.count);
        return s;
    }

    public String replace(CharSequence target, CharSequence replacement) {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(count, valueOf(target).count, valueOf(replacement).count);
        return s;
    }

    public String[] split(String regex, int limit) {
        String[] a = new String[limit+1];
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(this.count, regex.count);
        a[0] = s;
        return a;
    }

    public String[] split(String regex) {
        String[] a = new String[1];
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(this.count, regex.count);
        a[0] = s;
        return a;
    }

    public String toLowerCase(Locale locale) {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(this.count, locale);
        return s;
    }

    public String toLowerCase() {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(this.count);
        return s;
    }

    public String toUpperCase(Locale locale) {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(this.count, locale);
        return s;
    }

    public String toUpperCase() {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(this.count);
        return s;
    }

    public String trim() {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(this.count);
        return s;
    }

    // No need for a signature. The real method is exactly what we want.
    // public String toString() {return this;}

    public char[] toCharArray() {
        return new char[] { (char) count };
    }

    public static String format(String format, Object... args) {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(format.count, valueOf(args[0]).count);
        return s;
    }

    public static String format(Locale l, String format, Object... args) {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf(format.count, valueOf(args[0]).count, l);
        return s;
    }

    public static String valueOf(Object obj) {
        int length = obj.toString().length();
        String s = new String();
        s.count = length;
        return s;
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
        return new String(0, 1, c);
    }

    public static String valueOf(int i) {
        String s = new String();
        s.count = i;
        return s;
    }

    public static String valueOf(long l) {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf((int) l);
        return s;
    }

    public static String valueOf(float f) {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf((int) f);
        return s;
    }

    public static String valueOf(double d) {
        String s = new String();
        s.count = MockNativeMethods.intFunctionOf((int) d);
        return s;
    }

    public String intern() {
        return this;
    }
}
