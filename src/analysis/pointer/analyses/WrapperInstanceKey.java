package analysis.pointer.analyses;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

public interface WrapperInstanceKey extends InstanceKey {
    public InstanceKey getInnerIK();
}
