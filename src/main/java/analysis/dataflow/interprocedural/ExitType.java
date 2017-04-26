package analysis.dataflow.interprocedural;

/**
 * Key indicating the type of procedure termination
 */
public enum ExitType {
    /**
     * Exit edge key for normal termination
     */
    NORMAL("NT"),
    /**
     * Exit edge key for exceptional termination
     */
    EXCEPTIONAL("EX");

    /**
     * Short name for the exit type
     */
    private final String type;

    /**
     * Create a new type for the given string.
     * 
     * @param type
     *            Short name for exit type
     */
    private ExitType(String type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return type;
    }
}
