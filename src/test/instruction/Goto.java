package test.instruction;

public class Goto {
    static int x;
    static boolean y = true;

    public static void main(String[] args) {

        label: for (int i = 0; i < 10; i++) {
            if (y) {
                break label;
            }
        }
        x = 4;
    }
}
