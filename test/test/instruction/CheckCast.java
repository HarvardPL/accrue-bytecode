package test.instruction;

public class CheckCast {

    static CheckCast x = new CheckCast();
    static CheckCastSub y;

    public static void main(String[] args) {
        y = (CheckCastSub) x;
    }

    class CheckCastSub extends CheckCast {

    }
}
