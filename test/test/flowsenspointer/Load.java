package test.flowsenspointer;

public class Load {

    public Object f = new Object();

    public static void main(String[] args) {

        Load s = new Load();
        @SuppressWarnings("unused")
        Object o = s.f;
        //o.hashCode();

        Load s2 = new Load();

        // Object o2 = s2.f;
        // o2.hashCode();
    }
}
