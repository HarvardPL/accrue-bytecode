package string.tests;

public class CheckCastStringBuilder {
    public static void main(String[] args) {
        StringBuilder s1 = new StringBuilder("foo");
        StringBuilder s2 = (StringBuilder) s1;
        bar(s2.toString());
    }

    private static void bar(String string) {
        // TODO Auto-generated method stub

    }

}
