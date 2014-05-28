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

/**
 * Model of java.lang.System methods.
 */
public class System {

    private static void registerNatives() {
        // intentionally blank
    }

    static {
        registerNatives();
    }

    public final static InputStream in = new MockInputStream();
    public final static PrintStream out = new MockPrintStream(new MockOutputStream());
    public final static PrintStream err = new MockPrintStream(new MockOutputStream());

    @SuppressWarnings("unused")
    private static volatile SecurityManager security = null;

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
            throw new NullPointerException();
        }

        if (src == dest) {
            // same array copy into a temp array first
            if (src instanceof Object[]) {
                Object[] s = (Object[]) src;
                Object[] temp = new Object[length];
                for (int i = 0; i < length; i++)
                    temp[i] = s[i];
                src = temp;
            } else if (src instanceof byte[]) {
                byte[] s = (byte[]) src;
                byte[] temp = new byte[length];
                for (int i = 0; i < length; i++)
                    temp[i] = s[i];
                src = temp;
            } else if (src instanceof short[]) {
                short[] s = (short[]) src;
                short[] temp = new short[length];
                for (int i = 0; i < length; i++)
                    temp[i] = s[i];
                src = temp;
            } else if (src instanceof int[]) {
                int[] s = (int[]) src;
                int[] temp = new int[length];
                for (int i = 0; i < length; i++)
                    temp[i] = s[i];
                src = temp;
            } else if (src instanceof long[]) {
                long[] s = (long[]) src;
                long[] temp = new long[length];
                for (int i = 0; i < length; i++)
                    temp[i] = s[i];
                src = temp;
            } else if (src instanceof float[]) {
                float[] s = (float[]) src;
                float[] temp = new float[length];
                for (int i = 0; i < length; i++)
                    temp[i] = s[i];
                src = temp;
            } else if (src instanceof double[]) {
                double[] s = (double[]) src;
                double[] temp = new double[length];
                for (int i = 0; i < length; i++)
                    temp[i] = s[i];
                src = temp;
            } else if (src instanceof boolean[]) {
                boolean[] s = (boolean[]) src;
                boolean[] temp = new boolean[length];
                for (int i = 0; i < length; i++)
                    temp[i] = s[i];
                src = temp;
            } else if (src instanceof char[]) {
                char[] s = (char[]) src;
                char[] temp = new char[length];
                for (int i = 0; i < length; i++)
                    temp[i] = s[i];
                src = temp;
            }
            srcPos = 0;
        }

        if (src instanceof Object[] && dest instanceof Object[]) {
            if (length < 0) {
                throw new IndexOutOfBoundsException();
            }
            Object[] s = (Object[]) src;
            Object[] d = (Object[]) dest;
            for (int i = 0; i < length; i++)
                d[i + destPos] = s[i + srcPos];
        } else if (src instanceof byte[] && dest instanceof byte[]) {
            if (length < 0) {
                throw new IndexOutOfBoundsException();
            }
            byte[] s = (byte[]) src;
            byte[] d = (byte[]) dest;
            for (int i = 0; i < length; i++)
                d[i + destPos] = s[i + srcPos];
        } else if (src instanceof short[] && dest instanceof short[]) {
            if (length < 0) {
                throw new IndexOutOfBoundsException();
            }
            short[] s = (short[]) src;
            short[] d = (short[]) dest;
            for (int i = 0; i < length; i++)
                d[i + destPos] = s[i + srcPos];
        } else if (src instanceof int[] && dest instanceof int[]) {
            if (length < 0) {
                throw new IndexOutOfBoundsException();
            }
            int[] s = (int[]) src;
            int[] d = (int[]) dest;
            for (int i = 0; i < length; i++)
                d[i + destPos] = s[i + srcPos];
        } else if (src instanceof long[] && dest instanceof long[]) {
            if (length < 0) {
                throw new IndexOutOfBoundsException();
            }
            long[] s = (long[]) src;
            long[] d = (long[]) dest;
            for (int i = 0; i < length; i++)
                d[i + destPos] = s[i + srcPos];
        } else if (src instanceof float[] && dest instanceof float[]) {
            if (length < 0) {
                throw new IndexOutOfBoundsException();
            }
            float[] s = (float[]) src;
            float[] d = (float[]) dest;
            for (int i = 0; i < length; i++)
                d[i + destPos] = s[i + srcPos];
        } else if (src instanceof double[] && dest instanceof double[]) {
            if (length < 0) {
                throw new IndexOutOfBoundsException();
            }
            double[] s = (double[]) src;
            double[] d = (double[]) dest;
            for (int i = 0; i < length; i++)
                d[i + destPos] = s[i + srcPos];
        } else if (src instanceof boolean[] && dest instanceof boolean[]) {
            if (length < 0) {
                throw new IndexOutOfBoundsException();
            }
            boolean[] s = (boolean[]) src;
            boolean[] d = (boolean[]) dest;
            for (int i = 0; i < length; i++)
                d[i + destPos] = s[i + srcPos];
        } else if (src instanceof char[] && dest instanceof char[]) {
            if (length < 0) {
                throw new IndexOutOfBoundsException();
            }
            char[] s = (char[]) src;
            char[] d = (char[]) dest;
            for (int i = 0; i < length; i++)
                d[i + destPos] = s[i + srcPos];
        } else {
            // src is not an array,
            // dest is not an array, or
            // src and dest are arrays of different types
            throw new ArrayStoreException();
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
}