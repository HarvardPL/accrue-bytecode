package analysis.dataflow.flowsensitizer;


public interface LatticeJoin<T> {

    public abstract T join(T that);
}
