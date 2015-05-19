package analysis.pointer.graph.strings;

public class EscapedStringBuilderLocationReplica implements StringLikeLocationReplica {

    private static final EscapedStringBuilderLocationReplica ESCAPED_REPLICA = new EscapedStringBuilderLocationReplica();

    public static StringLikeLocationReplica make() {
        return ESCAPED_REPLICA;
    }

    private EscapedStringBuilderLocationReplica() {
        // empty
    }

}
