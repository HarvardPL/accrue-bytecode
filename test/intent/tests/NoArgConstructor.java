package intent.tests;

import android.content.Intent;

public class NoArgConstructor {
    public static void main(String[] args) {
        Intent i = new Intent();
        bar(i);
    }

    public static void bar(Intent i) {
        // Intentionally blank
    }
}
