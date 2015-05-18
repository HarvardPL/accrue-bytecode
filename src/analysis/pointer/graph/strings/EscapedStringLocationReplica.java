package analysis.pointer.graph.strings;

public class EscapedStringLocationReplica implements StringLikeLocationReplica {

    private static final EscapedStringLocationReplica ESCAPED_REPLICA = new EscapedStringLocationReplica();

    public static StringLikeLocationReplica make() {
        return ESCAPED_REPLICA;
    }

    private EscapedStringLocationReplica() {
        // empty
    }

}
