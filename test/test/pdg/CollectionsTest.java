package test.pdg;

import java.util.Map;
import java.util.TreeMap;

public class CollectionsTest {

    static String x = "PUBLIC";
    static String y = "SECRET";

    public static void main(String[] args) {
        OtherClass oc = new OtherClass();
        Map<String, String> m1 = new TreeMap<>();
        Map<String, String> m2 = oc.baz();
        m1.put("key1", x);
        m2.put("key2", y);
        bar(m1.get("key1"));
    }

    private static void bar(String string) {

    }

    private static class OtherClass {
        public OtherClass() {
            // TODO Auto-generated constructor stub
        }

        public Map<String, String> baz() {
            return new TreeMap<>();
        }
    }
}