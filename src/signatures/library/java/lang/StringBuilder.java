package signatures.library.java.lang;


public final class StringBuilder extends AbstractStringBuilder implements java.io.Serializable {

    static final long serialVersionUID = 4383685877147921099L;

    StringBuilder append(StringBuilder sb) {
        this.count = this.count + sb.count;
        return this;
    }

    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        s.defaultWriteObject();
        s.writeInt(count);
        // The count field contains all the information about this StringBuilder, no need for the value field for dependency purposes
        // s.writeObject(value);
    }

    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        count = s.readInt() + ((char[]) s.readObject())[0];
    }
}