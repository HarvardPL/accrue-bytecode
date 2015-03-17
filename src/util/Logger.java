package util;

import java.util.Stack;

public class Logger {
    private static final Stack<Boolean> stack;

    static {
        stack = new Stack<>();
        stack.push(true);
    }

    private static Boolean shouldLog() {
        return stack.peek();
    }

    public static void push(Boolean b) {
        stack.push(b);
    }

    public static void pop() {
        stack.pop();
    }

    public static void println(String s) {
        if (shouldLog()) {
            System.err.println("[" + Thread.currentThread().getId() + "]" + s);
        }
    }
}
