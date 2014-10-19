package test.pdg.sig;

import java.math.BigInteger;

public class BigIntegerIntValueSubclass {

    static BigInteger foo;
    static int bar;

    public static void main(String[] args) {
        foo = new BigIntegerSubClass("45");
        bar = foo.intValue();
    }

    /**
     * This class should prevent BigInteger from being added to the set of immutable wrappers. The subclass probably
     * could screw the immutability up.
     */
    public static class BigIntegerSubClass extends BigInteger {

        private static final long serialVersionUID = -7442425342352487210L;

        public BigIntegerSubClass(String val) {
            super(val);
        }

    }

}
