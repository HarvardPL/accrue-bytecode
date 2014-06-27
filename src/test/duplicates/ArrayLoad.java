package test.duplicates;

public class ArrayLoad {
    static boolean bar = false;
    static boolean foo = false;

    public static void main(String[] args) {
        ArrayLoad[] sa = new ArrayLoad[4];
        ArrayLoad s1 = sa[3];
        ArrayLoad s2 = sa[1];

        s1.baz();
        s2.baz();
    }

    public void baz() {
        // TODO Auto-generated method stub
    }
}