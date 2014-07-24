package string.tests;

public class ArrayLoadStringBuilder {
    public static void main(String[] args) {
        StringBuilder[] foo = new StringBuilder[] { new StringBuilder("foo") };
        bar(foo[0].toString());
    }

    private static void bar(String string) {
        // TODO Auto-generated method stub

    }

}
