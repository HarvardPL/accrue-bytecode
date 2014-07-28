package string.tests;

public class GetString {
    String s = "foo";

    public static void main(String[] args) {
        GetString s1 = new GetString();
        bar(s1.s);
    }

    private static void bar(@SuppressWarnings("unused")
    String s) {
        // TODO Auto-generated method stub
    }
}
