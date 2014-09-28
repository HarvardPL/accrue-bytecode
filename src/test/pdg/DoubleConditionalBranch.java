package test.pdg;

public class DoubleConditionalBranch {
    static boolean x;
    static boolean a;
    static int y;
    static int z;
    static int[] foo = new int[1];
    static int[] foo2 = new int[1];
    static int bar;
    static int bar2;

    public static void main(String[] args) {

        if (x) {
            bar = foo.length;
        }
        z = 42;

        if (a) {
            bar2 = foo2.length;
        }
        y = 48;
        //        if (a) {
        //            bar = foo.length;
        //            y = 8;
        //        }
        //        else {
        //            y = 9;
        //        }
        //        z = 48;

    }
}
