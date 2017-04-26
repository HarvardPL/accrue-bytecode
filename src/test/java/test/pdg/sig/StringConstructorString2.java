package test.pdg.sig;

public class StringConstructorString2 {

    static String foo;
    static String bar;

    public static void main(String[] args) {
        bar = new String();
        foo = new String(bar);
    }

}
