package signatures.library.java.lang;


public final class StringBuilder extends AbstractStringBuilder implements java.io.Serializable {

    static final long serialVersionUID = 4383685877147921099L;

    public StringBuilder() {
        super(16);
    }

    public StringBuilder(int capacity) {
        super(capacity);
    }

    public StringBuilder(String str) {
        super(str.length() + 16);
        append(str);
    }

    public StringBuilder(CharSequence seq) {
        this(seq.length() + 16);
        append(seq);
    }

    @Override
    public StringBuilder append(Object obj) {
        return append(String.valueOf(obj));
    }

    @Override
    public StringBuilder append(String str) {
        super.append(str);
        return this;
    }

    private StringBuilder append(StringBuilder sb) {
        this.count = MockNativeMethods.intFunctionOf(this.count, sb.count);
        return this;
    }

    @Override
    public StringBuilder append(StringBuffer sb) {
        super.append(sb);
        return this;
    }

    @Override
    public StringBuilder append(CharSequence s) {
        if (s == null) {
            s = "null";
        }
        if (s instanceof String) {
            return this.append((String) s);
        }
        if (s instanceof StringBuffer) {
            return this.append((StringBuffer) s);
        }
        if (s instanceof StringBuilder) {
            return this.append((StringBuilder) s);
        }
        return this.append(s, 0, s.length());
    }

    @Override
    public StringBuilder append(CharSequence s, int start, int end) {
        super.append(s, start, end);
        return this;
    }

    @Override
    public StringBuilder append(char str[]) {
        super.append(str);
        return this;
    }

    @Override
    public StringBuilder append(char str[], int offset, int len) {
        super.append(str, offset, len);
        return this;
    }

    @Override
    public StringBuilder append(boolean b) {
        super.append(b);
        return this;
    }

    @Override
    public StringBuilder append(char c) {
        super.append(c);
        return this;
    }

    @Override
    public StringBuilder append(int i) {
        super.append(i);
        return this;
    }

    @Override
    public StringBuilder append(long lng) {
        super.append(lng);
        return this;
    }

    @Override
    public StringBuilder append(float f) {
        super.append(f);
        return this;
    }

    @Override
    public StringBuilder append(double d) {
        super.append(d);
        return this;
    }

    @Override
    public StringBuilder appendCodePoint(int codePoint) {
        super.appendCodePoint(codePoint);
        return this;
    }

    @Override
    public StringBuilder delete(int start, int end) {
        super.delete(start, end);
        return this;
    }

    @Override
    public StringBuilder deleteCharAt(int index) {
        super.deleteCharAt(index);
        return this;
    }

    @Override
    public StringBuilder replace(int start, int end, String str) {
        super.replace(start, end, str);
        return this;
    }

    @Override
    public StringBuilder insert(int index, char str[], int offset, int len) {
        super.insert(index, str, offset, len);
        return this;
    }

    @Override
    public StringBuilder insert(int offset, Object obj) {
        return insert(offset, String.valueOf(obj));
    }

    @Override
    public StringBuilder insert(int offset, String str) {
        super.insert(offset, str);
        return this;
    }

    @Override
    public StringBuilder insert(int offset, char str[]) {
        super.insert(offset, str);
        return this;
    }

    @Override
    public StringBuilder insert(int dstOffset, CharSequence s) {
        if (s == null) {
            s = "null";
        }
        if (s instanceof String) {
            return this.insert(dstOffset, (String) s);
        }
        return this.insert(dstOffset, s, 0, s.length());
    }

    @Override
    public StringBuilder insert(int dstOffset, CharSequence s, int start, int end) {
        super.insert(dstOffset, s, start, end);
        return this;
    }

    @Override
    public StringBuilder insert(int offset, boolean b) {
        super.insert(offset, b);
        return this;
    }

    @Override
    public StringBuilder insert(int offset, char c) {
        super.insert(offset, c);
        return this;
    }

    @Override
    public StringBuilder insert(int offset, int i) {
        return insert(offset, String.valueOf(i));
    }

    @Override
    public StringBuilder insert(int offset, long l) {
        return insert(offset, String.valueOf(l));
    }

    @Override
    public StringBuilder insert(int offset, float f) {
        return insert(offset, String.valueOf(f));
    }

    @Override
    public StringBuilder insert(int offset, double d) {
        return insert(offset, String.valueOf(d));
    }

    @Override
    public int indexOf(String str) {
        return indexOf(str, 0);
    }

    @Override
    public int indexOf(String str, int fromIndex) {
        // Signature
        return super.indexOf(str, fromIndex);
    }

    @Override
    public int lastIndexOf(String str) {
        return lastIndexOf(str, count);
    }

    /**
     * @throws NullPointerException {@inheritDoc}
     */
    @Override
    public int lastIndexOf(String str, int fromIndex) {
        if (str == null) {
            throw new NullPointerException();
        }
        return super.indexOf(str, fromIndex);
    }

    @Override
    public StringBuilder reverse() {
        super.reverse();
        return this;
    }

    // No signature needed does the right thing, also it is hard to write this signature
    //    @Override
    //    public String toString() {
    //        // Create a copy, don't share the array
    //        return new String(value, 0, count);
    //    }

    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        s.defaultWriteObject();
        s.writeInt(count);
        // The count field contains all the information about this StringBuilder, no need for the value field for dependency purposes
        // s.writeObject(value);
    }

    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        count = MockNativeMethods.intFunctionOf(s.readInt(), ((char[]) s.readObject())[0]);
    }
}