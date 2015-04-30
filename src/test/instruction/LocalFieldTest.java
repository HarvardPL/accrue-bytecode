package test.instruction;

public class LocalFieldTest {
    public String field;

    public LocalFieldTest() {
        this.field = "test.instruction.LocalFieldTest";
    }

    public static void main(String[] args) throws InstantiationException, IllegalAccessException,
                                          ClassNotFoundException {
        {
            LocalFieldTest lft = new LocalFieldTest();
            Object oBranch = Class.forName(lft.field).newInstance();
        }
    }

}
