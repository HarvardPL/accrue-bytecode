package string.tests;

public class GetStaticString {
    static String s = "foo";

    public static void main(String[] args) {
        String s1 = GetStaticString.s;
        bar(s1);
    }

    private static void bar(@SuppressWarnings("unused")
    String s) {
        // TODO Auto-generated method stub
    }
}
