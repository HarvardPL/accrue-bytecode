package test.instruction;

public class Throw {

    public static void main(String[] args) throws MyException {
        throw new MyException();
    }

    static class MyException extends Throwable {

    }
}
