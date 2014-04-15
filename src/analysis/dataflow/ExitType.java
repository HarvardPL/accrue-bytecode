package analysis.dataflow;

/**
 * Key indicating the type of procedure termination
 */
public class ExitType {
    /**
     * Exit edge key for normal termination
     */
    public static final ExitType NORM_TERM = new ExitType("NT");
    /**
     * Exit edge key for exceptional termination
     */
    public static final ExitType EXCEPTION = new ExitType("EX");

    /**
     * String indicating exit type
     */
    private final String type;

    /**
     * Create a new type for the given string.
     * 
     * @param type
     *            string representation of the type of exit
     */
    private ExitType(String type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return type;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((type == null) ? 0 : type.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ExitType other = (ExitType) obj;
        if (type == null) {
            if (other.type != null)
                return false;
        } else if (!type.equals(other.type))
            return false;
        return true;
    }

}
