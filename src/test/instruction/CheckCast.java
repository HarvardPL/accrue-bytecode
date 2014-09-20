package test.instruction;

public class CheckCast {

    static CheckCast x;
    static CheckCastSub y;

    public static void main(String[] args) {
        y = (CheckCastSub) x;
    }

    class CheckCastSub extends CheckCast {

    }
}
