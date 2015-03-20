/*******************************************************************************
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package signatures.library.java.lang;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Properties;

import signatures.SingletonExceptions;

/**
 * Model of java.lang.System methods.
 */
public class System {

    private static void registerNatives() {
        // intentionally blank
    }


    private static Properties props = new Properties();
    public final static InputStream in = new MockInputStream();
    public final static PrintStream out = new MockPrintStream(new MockOutputStream());
    public final static PrintStream err = new MockPrintStream(new MockOutputStream());

    private static volatile SecurityManager security = getSecurityManager();

    public static SecurityManager getSecurityManager() {
        if (security == null) {
            security = new SecurityManager();
        }
        return security;
    }



    static {
        registerNatives();
        props.setProperty("foo", "bar");
    }

    /**
     * Java implementation of {@link System#arraycopy(Object, int, Object, int, int)} used as an analysis signature
     *
     * @param src
     *            source array
     * @param srcPos
     *            start position in the source array
     * @param dest
     *            destination array
     * @param destPos
     *            start position in the dest array
     * @param length
     *            number of elements to copy
     */
    static void arraycopy(Object src, int srcPos, Object dest, int destPos, int length) {
        if (src == null || dest == null) {
            throw SingletonExceptions.NULL_POINTER;
        }

        //        if (src == dest) {
        //            // same array copy into a temp array first
        //            if (src instanceof Object[]) {
        //                Object[] s = (Object[]) src;
        //                Object[] temp = new Object[length];
        //                for (int i = 0; i < length; i++)
        //                    temp[i] = s[i];
        //                src = temp;
        //            } else if (src instanceof byte[]) {
        //                byte[] s = (byte[]) src;
        //                byte[] temp = new byte[length];
        //                for (int i = 0; i < length; i++)
        //                    temp[i] = s[i];
        //                src = temp;
        //            } else if (src instanceof short[]) {
        //                short[] s = (short[]) src;
        //                short[] temp = new short[length];
        //                for (int i = 0; i < length; i++)
        //                    temp[i] = s[i];
        //                src = temp;
        //            } else if (src instanceof int[]) {
        //                int[] s = (int[]) src;
        //                int[] temp = new int[length];
        //                for (int i = 0; i < length; i++)
        //                    temp[i] = s[i];
        //                src = temp;
        //            } else if (src instanceof long[]) {
        //                long[] s = (long[]) src;
        //                long[] temp = new long[length];
        //                for (int i = 0; i < length; i++)
        //                    temp[i] = s[i];
        //                src = temp;
        //            } else if (src instanceof float[]) {
        //                float[] s = (float[]) src;
        //                float[] temp = new float[length];
        //                for (int i = 0; i < length; i++)
        //                    temp[i] = s[i];
        //                src = temp;
        //            } else if (src instanceof double[]) {
        //                double[] s = (double[]) src;
        //                double[] temp = new double[length];
        //                for (int i = 0; i < length; i++)
        //                    temp[i] = s[i];
        //                src = temp;
        //            } else if (src instanceof boolean[]) {
        //                boolean[] s = (boolean[]) src;
        //                boolean[] temp = new boolean[length];
        //                for (int i = 0; i < length; i++)
        //                    temp[i] = s[i];
        //                src = temp;
        //            } else if (src instanceof char[]) {
        //                char[] s = (char[]) src;
        //                char[] temp = new char[length];
        //                for (int i = 0; i < length; i++)
        //                    temp[i] = s[i];
        //                src = temp;
        //            }
        //            srcPos = 0;
        //        }

        if (src instanceof Object[] && dest instanceof Object[]) {
            if (length < 0) {
                throw SingletonExceptions.INDEX_OUT_OF_BOUNDS;
            }
            Object[] s = (Object[]) src;
            Object[] d = (Object[]) dest;
            for (int i = 0; i < length; i++) {
                d[i + destPos] = s[i + srcPos];
            }
        } else if (src instanceof byte[] && dest instanceof byte[]) {
            if (length < 0) {
                throw SingletonExceptions.INDEX_OUT_OF_BOUNDS;
            }
            byte[] s = (byte[]) src;
            byte[] d = (byte[]) dest;
            for (int i = 0; i < length; i++) {
                d[i + destPos] = s[i + srcPos];
            }
        } else if (src instanceof short[] && dest instanceof short[]) {
            if (length < 0) {
                throw SingletonExceptions.INDEX_OUT_OF_BOUNDS;
            }
            short[] s = (short[]) src;
            short[] d = (short[]) dest;
            for (int i = 0; i < length; i++) {
                d[i + destPos] = s[i + srcPos];
            }
        } else if (src instanceof int[] && dest instanceof int[]) {
            if (length < 0) {
                throw SingletonExceptions.INDEX_OUT_OF_BOUNDS;
            }
            int[] s = (int[]) src;
            int[] d = (int[]) dest;
            for (int i = 0; i < length; i++) {
                d[i + destPos] = s[i + srcPos];
            }
        } else if (src instanceof long[] && dest instanceof long[]) {
            if (length < 0) {
                throw SingletonExceptions.INDEX_OUT_OF_BOUNDS;
            }
            long[] s = (long[]) src;
            long[] d = (long[]) dest;
            for (int i = 0; i < length; i++) {
                d[i + destPos] = s[i + srcPos];
            }
        } else if (src instanceof float[] && dest instanceof float[]) {
            if (length < 0) {
                throw SingletonExceptions.INDEX_OUT_OF_BOUNDS;
            }
            float[] s = (float[]) src;
            float[] d = (float[]) dest;
            for (int i = 0; i < length; i++) {
                d[i + destPos] = s[i + srcPos];
            }
        } else if (src instanceof double[] && dest instanceof double[]) {
            if (length < 0) {
                throw SingletonExceptions.INDEX_OUT_OF_BOUNDS;
            }
            double[] s = (double[]) src;
            double[] d = (double[]) dest;
            for (int i = 0; i < length; i++) {
                d[i + destPos] = s[i + srcPos];
            }
        } else if (src instanceof boolean[] && dest instanceof boolean[]) {
            if (length < 0) {
                throw SingletonExceptions.INDEX_OUT_OF_BOUNDS;
            }
            boolean[] s = (boolean[]) src;
            boolean[] d = (boolean[]) dest;
            for (int i = 0; i < length; i++) {
                d[i + destPos] = s[i + srcPos];
            }
        } else if (src instanceof char[] && dest instanceof char[]) {
            if (length < 0) {
                throw SingletonExceptions.INDEX_OUT_OF_BOUNDS;
            }
            char[] s = (char[]) src;
            char[] d = (char[]) dest;
            for (int i = 0; i < length; i++) {
                d[i + destPos] = s[i + srcPos];
            }
        } else {
            // src is not an array,
            // dest is not an array, or
            // src and dest are arrays of different types
            throw SingletonExceptions.ARRAY_STORE;
        }
    }

    public static long currentTimeMillis() {
        return 328555620000L;
    }

    public static void exit(@SuppressWarnings("unused") int i) {
        // intentionally left blank
    }

    /**
     * Input stream that does nothing used to System.in
     */
    private static class MockInputStream extends InputStream {
        public MockInputStream() {
        }

        @SuppressWarnings("unused")
        @Override
        public int read() throws IOException {
            return -1;
        }
    }

    /**
     * Print stream that does nothing used to System.out and System.err
     */
    public static class MockPrintStream extends PrintStream {
        public MockPrintStream(OutputStream out) {
            super(out);
        }
    }

    /**
     * Output stream that does nothing
     */
    private static class MockOutputStream extends OutputStream {

        public MockOutputStream() {
        }

        @SuppressWarnings("unused")
        @Override
        public void write(int b) throws IOException {
            // intentionally left blank
        }
    }

    public static void setProperties(Properties props) {
        System.props = props;
    }

    public static java.lang.String setProperty(java.lang.String key, java.lang.String value) {
        return (java.lang.String) props.setProperty(key, value);
    }

    public static java.lang.String clearProperty(java.lang.String key) {
        return (java.lang.String) props.remove(key);
    }

    public static java.lang.String getProperty(java.lang.String key) {
        return props.getProperty(key);
    }

    public static java.lang.String getProperty(java.lang.String key, java.lang.String def) {
        return props.getProperty(key, def);
    }
}
