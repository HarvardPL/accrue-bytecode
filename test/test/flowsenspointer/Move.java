package test.flowsenspointer;

public class Move {

    Object f;

    public static void main(String[] args) {
        Move s = new Move();
        @SuppressWarnings("unused")
        Object s2 = s;
        s2.hashCode();
    }
}
