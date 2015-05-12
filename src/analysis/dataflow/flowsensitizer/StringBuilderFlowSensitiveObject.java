package analysis.dataflow.flowsensitizer;

import com.ibm.wala.classLoader.IMethod;

public class StringBuilderFlowSensitiveObject {
    private final IMethod method;
    private final int defTime;
    private final int time;

    public static StringBuilderFlowSensitiveObject make(IMethod method, int defTime, int time) {
        return new StringBuilderFlowSensitiveObject(method, defTime, time);
    }

    private StringBuilderFlowSensitiveObject(IMethod method, int defTime, int time) {
        this.defTime = defTime;
        this.time = time;
        this.method = method;
    }

    @Override
    public String toString() {
        return "StringBuilderFlowSensitiveObject [method=" + method + ", defTime=" + defTime + ", time=" + time + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + defTime;
        result = prime * result + ((method == null) ? 0 : method.hashCode());
        result = prime * result + time;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof StringBuilderFlowSensitiveObject)) {
            return false;
        }
        StringBuilderFlowSensitiveObject other = (StringBuilderFlowSensitiveObject) obj;
        if (defTime != other.defTime) {
            return false;
        }
        if (method == null) {
            if (other.method != null) {
                return false;
            }
        }
        else if (!method.equals(other.method)) {
            return false;
        }
        if (time != other.time) {
            return false;
        }
        return true;
    }

}
