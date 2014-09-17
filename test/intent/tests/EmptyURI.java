package intent.tests;

import android.content.Intent;
import android.net.Uri;

public class EmptyURI {
    public static void main(String[] args) {
        Uri uri = Uri.EMPTY;
        Intent i = new Intent();
        i.setData(uri);
        bar(i);
    }

    public static void bar(Intent i) {
        // Intentionally blank
    }
}
