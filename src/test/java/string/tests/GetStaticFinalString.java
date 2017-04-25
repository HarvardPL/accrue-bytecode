package string.tests;

public class GetStaticFinalString {
    static final String s = "foo";

    public static void main(String[] args) {
        String s1 = GetStaticFinalString.s;
        bar(s1);
    }

    private static void bar(@SuppressWarnings("unused")
    String s) {
        // TODO Auto-generated method stub
    }
}
