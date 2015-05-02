package analysis.dataflow.flowsensitizer;

import java.util.Collection;
import java.util.Iterator;

public final class LatticeJoinMethods {
    public static <T extends LatticeJoin<T>> T joinCollection(T bottom, Collection<T> c) {
        if (c.size() == 0) {
            return bottom;
        }
        else if (c.size() == 1) {
            return c.iterator().next();
        }
        else {
            Iterator<T> it = c.iterator();
            T f = it.next();

            while (it.hasNext()) {
                // XXX: this could be made more efficient
                f = f.join(it.next());
            }

            return f;
        }
    }

}
