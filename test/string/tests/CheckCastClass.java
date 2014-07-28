package string.tests;

public class CheckCastClass {
    public static void main(String[] args) {
        Class s1 = String.class;
        Class s2 = (Class) s1;
        bar(s2.getName());
    }

    private static void bar(String string) {
        // TODO Auto-generated method stub

    }

}
