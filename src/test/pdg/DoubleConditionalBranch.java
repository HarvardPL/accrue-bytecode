package test.pdg;

public class DoubleConditionalBranch {
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

        if (x) {
            y = 8;
        }
        else {
            y = 9;
        }
        z = 48;

    }
}
