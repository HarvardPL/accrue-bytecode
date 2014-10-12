package test.instruction;

public class InstanceOf {
    static boolean x;
    static InstanceOf y = new InstanceOf();

    public static void main(String[] args) {
        if (x) {
            y = new InstanceOfSub();
        }
        x = y instanceof InstanceOfSub;

    }

    static class InstanceOfSub extends InstanceOf {

    }
}
