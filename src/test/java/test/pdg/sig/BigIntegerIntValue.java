package test.pdg.sig;

import java.math.BigInteger;

public class BigIntegerIntValue {

    static BigInteger foo;
    static int bar;

    public static void main(String[] args) {
        foo = new BigInteger("45");
        bar = foo.intValue();
    }

}
