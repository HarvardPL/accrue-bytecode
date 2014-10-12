package test.instruction;

public class PutField {
    int x;
    static boolean b = true;

    public static void main(String[] args) {
        PutField y;
        if (b) {
            y = new PutField();
        } else {
            y = new PutField();
        }
        y.x = 42;
    }
}
