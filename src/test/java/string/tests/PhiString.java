package string.tests;

public class PhiString {
    static int x = 0;

    public static void main(String[] args) {
        String s1;
        if (x == 1) {
            s1 = "foo1";
        }
        else {
            s1 = "foo2";
        }
        bar(s1);
    }

    private static void bar(@SuppressWarnings("unused")
    String s) {
        // TODO Auto-generated method stub
    }
}
