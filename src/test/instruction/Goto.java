package test.instruction;

public class Goto {
    static int x;
    static boolean y;

    public static void main(String[] args) {

        label: for (int i = 0; i < 10; i++) {
            if (y) {
                break label;
            }
        }

    }
}
