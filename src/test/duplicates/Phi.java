package test.duplicates;

public class Phi {
    static boolean bar = false;
    static boolean foo = false;

    public static void main(String[] args) {
        Phi s00 = new Phi();
        Phi s01 = new Phi();
        Phi s1;
        Phi s2;
        if (bar) {
            s1 = s00;
        } else {
            s1 = s01;
        }

        if (foo) {
            s2 = s01;
        }
        else {
            s2 = s00;
        }

        s1.baz();
        s2.baz();
    }

    public void baz() {
        // TODO Auto-generated method stub
    }
}