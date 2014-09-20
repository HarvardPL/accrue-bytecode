package test.instruction;

public class InstanceOf {
    static boolean x;
    static InstanceOf y;

    public static void main(String[] args) {
        x = y instanceof InstanceOfSub;

    }

    class InstanceOfSub extends InstanceOf {

    }
}
