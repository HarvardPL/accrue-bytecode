package test.pointer;

import java.io.FileNotFoundException;
import java.io.IOException;


public class ExceptionalReturn {

    public static void main(String[] args) throws Exception {
        foo();
    }

    private static void foo() throws IOException {
        throw new FileNotFoundException();
    }
}
