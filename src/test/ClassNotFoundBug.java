package test;


public class ClassNotFoundBug {

    public static void main(String[] args) {
        try {
            throw new Throwable();
        } catch (Throwable t) {
            foo(t);
        }
    }

    private static void foo(Throwable t) {
        Throwable tfoo = t.getCause();
        tfoo.getCause();
    }
}
