package test.pointer;

public class ClassCast {

    public static void main(String[] args) {
        Object o1 = new ClassCast();
        @SuppressWarnings("unused")
        ClassCast o2 = (ClassCast) o1;        
    }

}
