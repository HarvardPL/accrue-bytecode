package test.instruction;

public class ArrayLoad {
    static ArrayLoad[] x;
    static ArrayLoad y;
    static int z;

    public static void main(String[] args) {
        x = new ArrayLoad[4];
        x[2] = new ArrayLoad();
        y = x[2];
    }
}
