package test.instruction;

public class RWSATest {

    public static void main(String[] args) throws InstantiationException, IllegalAccessException,
                                          ClassNotFoundException {
        //        {
        //            String foo = "foo";
        //            String bar = "bar";
        //            String baz = foo.concat(bar).concat("baz");
        //            String qux = baz.concat("qux");
        //            String quux = baz.concat("quux");
        //        }
        //
        //        {
        //            String packageName = "test.instruction.";
        //            String className = "RWSATest";
        //
        //            String fullyQualifiedClassName = packageName.concat(className);
        //            Object o = indirection(fullyQualifiedClassName);
        //            Object o2 = indirection(fullyQualifiedClassName);
        //        }

        {
            StringBuilder classNameSB = new StringBuilder();
            classNameSB.append("test.");
            classNameSB.append("instruction.");
            classNameSB.append("RWSATest");

            Object o2 = Class.forName(classNameSB.toString()).newInstance();
        }
    }

    //    public static Object indirection(String s) throws InstantiationException, IllegalAccessException,
    //                                              ClassNotFoundException {
    //        return Class.forName(s).newInstance();
    //    }
}
