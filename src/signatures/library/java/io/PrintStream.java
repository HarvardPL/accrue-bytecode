package signatures.library.java.io;

import java.io.FilterOutputStream;
import java.io.OutputStream;

public class PrintStream extends FilterOutputStream {
    public PrintStream(OutputStream out) {
        super(out);
    }

    @SuppressWarnings("unused")
    private void write(char buf[]) {
        // do nothing
    }

    @SuppressWarnings("unused")
    private void write(String s) {
        // do nothing
    }

    @SuppressWarnings("unused")
    private void newLine() {
        // do nothing
    }

    @Override
    public void close() {
        // do nothing
    }
}
