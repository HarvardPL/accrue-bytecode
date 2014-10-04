package signatures.library.java.lang;


public final class StringBuffer extends AbstractStringBuilder implements java.io.Serializable {

    static final long serialVersionUID = 3388685877147921107L;

    @Override
    public synchronized int capacity() {
        return super.capacity();
    }

    @Override
    public synchronized void ensureCapacity(int minimumCapacity) {
        super.ensureCapacity(minimumCapacity);
    }

    @Override
    public synchronized char charAt(int index) {
        return super.charAt(index);
    }


    @Override
    public synchronized void setCharAt(int index, char ch) {
        super.setCharAt(index, ch);
    }

    private static final java.io.ObjectStreamField[] serialPersistentFields = {
            new java.io.ObjectStreamField("value", char[].class), new java.io.ObjectStreamField("count", Integer.TYPE),
            new java.io.ObjectStreamField("shared", Boolean.TYPE), };

    private synchronized void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        java.io.ObjectOutputStream.PutField fields = s.putFields();
        fields.put("value", count);
        fields.put("count", count);
        fields.put("shared", false);
        s.writeFields();
    }

    /**
     * readObject is called to restore the state of the StringBuffer from a stream.
     */
    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        java.io.ObjectInputStream.GetField fields = s.readFields();
        count = fields.get("value", 0) + fields.get("count", 0);
    }
}
