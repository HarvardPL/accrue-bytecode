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

        /*
         * same code but branching test
         */
        StringBuilder classNameBranchSB = new StringBuilder();
        if (Math.random() <= 0.5)
        {
            classNameBranchSB.append("test.");
            classNameBranchSB.append("instruction.");
            classNameBranchSB.append("RWSATest");

        }
        else {
            classNameBranchSB.append("test.");
            classNameBranchSB.append("instruction.");
            classNameBranchSB.append("RWSATest");

        }
        Object oBranch = Class.forName(classNameBranchSB.toString()).newInstance();

        /*
         * looping test
         */
        StringBuilder classNameLoopSB = new StringBuilder();
        while (Math.random() < 0.5) {
            classNameLoopSB.append("test.");
            classNameLoopSB.append("instruction.");
            classNameLoopSB.append("RWSATest");
        }
        Object oLoop = Class.forName(classNameLoopSB.toString()).newInstance();

    }

    //    public static Object indirection(String s) throws InstantiationException, IllegalAccessException,
    //                                              ClassNotFoundException {
    //        return Class.forName(s).newInstance();
    //    }
}
