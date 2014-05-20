package signatures.library.java.lang;

import java.io.IOException;
import java.io.InputStream;

public class MockInputStream extends InputStream {

    @SuppressWarnings("unused")
    @Override
    public int read() throws IOException {
        return -1;
    }

}
