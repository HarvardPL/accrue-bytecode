package test.flowsenspointer;


public abstract class TestBaseClass {
    @SuppressWarnings("unused")
    static void pointsTo(Object arg4) {
        // intentional
    }

    @SuppressWarnings("unused")
    static void nonMostRecent(Object arg1) {
        // intentional
    }

    @SuppressWarnings("unused")
    static void mostRecent(Object arg2) {
        // intentional
    }

    @SuppressWarnings("unused")
    static void pointsToNull(Object arg3) {
        // intentional
    }
}
