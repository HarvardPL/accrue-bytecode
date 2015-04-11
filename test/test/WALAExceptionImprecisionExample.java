package test;

import java.io.IOException;

public class WALAExceptionImprecisionExample {

    public static Object f1;

    public static class Foo {

        private static Foo instance;

        private Foo() throws IOException {
            if (instance == null) {
                throw new IOException();
            }
        }

        public static Foo getInstance() throws RuntimeException {
            try {
                instance = new Foo();
            }
            catch (Exception e) {
                // Catches the IOException
                throw new RuntimeException(e.getMessage());
            }
            return instance;
        }
    }

    public static void main(String[] args) {
        try {
            f1 = Foo.getInstance();
        }
        catch (Exception e) {
            // e cannot point to IOException here
            throw new RuntimeException(e.getMessage());
        }
    }
}
