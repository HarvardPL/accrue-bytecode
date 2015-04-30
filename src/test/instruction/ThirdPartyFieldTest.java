package test.instruction;

public class ThirdPartyFieldTest {
    public static void main(String[] args) throws InstantiationException, IllegalAccessException,
                                          ClassNotFoundException {
        {
            Object oBranch = Class.forName(StaticFieldTest.field).newInstance();
        }
    }

}
