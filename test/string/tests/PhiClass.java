package string.tests;

public class PhiClass {
    static int x = 0;

    public static void main(String[] args) {
        Class s1;
        if (x == 1) {
            s1 = String.class;
        }
        else {
            s1 = PhiClass.class;
        }
        bar(s1.getName());
    }

    private static void bar(@SuppressWarnings("unused")
    String s) {
        // TODO Auto-generated method stub
    }
}
