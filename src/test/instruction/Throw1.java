package test.instruction;

public class Throw1 {

    static MyException x = new MyException();

    public static void main(String[] args) throws MyException {
        throw x;
    }

    static class MyException extends Throwable {

    }
}
