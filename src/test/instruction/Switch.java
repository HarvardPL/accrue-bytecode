package test.instruction;

public class Switch {
    static int x;
    static int z;
    public static void main(String[] args) {

        int y = 0;
        switch (x) {
        case 1:
            y = 42;
            break;
        case 2:
            y = 43;
        case 3:
            y = 44;

        }
        z = y;
    }
}
