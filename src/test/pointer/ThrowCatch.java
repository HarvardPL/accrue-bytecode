package test.pointer;

import java.io.FileNotFoundException;


public class ThrowCatch {

    public static void main(String[] args) {
        try {
            throw new FileNotFoundException();
        } catch (FileNotFoundException t) {
            foo(t);
        }
    }

    private static void foo(FileNotFoundException t) {
        String tfoo = t.getMessage();
        tfoo.length();
    }
}
