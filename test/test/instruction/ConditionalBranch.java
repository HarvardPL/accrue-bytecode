package test.instruction;

public class ConditionalBranch {
    static boolean x;
    static int y;
    static int z;

    public static void main(String[] args) {

        if (x) {
            y = 6;
        }
        else {
            y = 7;
        }
        z = 42;

    }
}
