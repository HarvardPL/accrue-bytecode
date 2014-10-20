package test.pdg.sig;

public class StringGetBytes {

    static String foo = "foo";
    static byte[] bar;

    public static void main(String[] args) {
        bar = foo.getBytes();
    }

}
