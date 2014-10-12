package test.pointer;

public class StaticFieldToLocal {

    static Object f = new Object();
    
	public static void main(String[] args) {
	    @SuppressWarnings("unused")
        Object o1 = f;
	    f = new Object();
	    @SuppressWarnings("unused")
        Object o2 = f;
	}
}
