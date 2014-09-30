package signatures.library.java.lang;

import java.nio.charset.Charset;
import java.util.Locale;


class MockNativeMethods {

    static native int intFunctionOf(byte a, int x, int y, Charset z);

    static native int intFunctionOf(int x);

    static native int intFunctionOf(int x, int y);

    static native int intFunctionOf(int count, boolean b);

    static native int intFunctionOf(int x, int y, int z);

    static native int intFunctionOf(int x, int y, int z, int w);

    static native int intFunctionOf(int x, int y, int z, int w, int u);

    static native int intFunctionOf(int x, Locale y);

    static native int intFunctionOf(int x, int y, Locale z);

    static native boolean booleanFunctionOf(int x);

    static native boolean booleanFunctionOf(int x, int y);

    static native boolean booleanFunctionOf(int x, int y, int z);

    static native boolean booleanFunctionOf(int x, int y, int z, int w);

    static native boolean booleanFunctionOf(int x, int y, int z, int w, int u);

    static native boolean booleanFunctionOf(int x, int y, int z, int w, int u, boolean b);

}

