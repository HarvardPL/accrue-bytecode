package analysis.pointer.analyses;

import util.print.PrettyPrinter;

import com.ibm.wala.classLoader.IClass;

/**
 * We want nicer printing so wrap the class in a lightweight wrapper
 */
class ClassWrapper {

    /**
     * Class to wrap
     */
    private IClass c;

    /**
     * Wrapper for the given class
     *
     * @param c class to wrap
     */
    ClassWrapper(IClass c) {
        assert c != null;
        this.c = c;
    }

    @Override
    public String toString() {
        return PrettyPrinter.typeString(c);
    }

    @Override
    public int hashCode() {
        return 31 + c.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        ClassWrapper other = (ClassWrapper) obj;
        return c == other.c;
    }
}
