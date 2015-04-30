package test.instruction;

public class StaticFieldTest {

    public static String field = "test.instruction.StaticFieldTest";

    public static void main(String[] args) throws InstantiationException, IllegalAccessException,
                                          ClassNotFoundException {
        {
            Object oBranch = Class.forName(StaticFieldTest.field).newInstance();
        }
    }
}
