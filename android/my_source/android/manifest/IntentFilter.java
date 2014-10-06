package android.manifest;

import java.util.Map;

public class IntentFilter {
    private final String actionName;
    private final String categoryName;
    /**
     * <pre>
     * <data android:scheme="string"
     *       android:host="string"
     *       android:port="string"
     *       android:path="string"
     *       android:pathPattern="string"
     *       android:pathPrefix="string"
     *       android:mimeType="string" />
     * </pre>
     */
    private final Map<String, String> data;
    
    public IntentFilter(String actionName, String categoryName, Map<String, String> data) {
        this.actionName = actionName;
        this.categoryName = categoryName;
        this.data = data;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[action=" + actionName);
        sb.append(", category=" + categoryName);
        if (data != null) {
            sb.append(", data=");
            sb.append(data);
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((actionName == null) ? 0 : actionName.hashCode());
        result = prime * result + ((categoryName == null) ? 0 : categoryName.hashCode());
        result = prime * result + ((data == null) ? 0 : data.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        IntentFilter other = (IntentFilter) obj;
        if (actionName == null) {
            if (other.actionName != null)
                return false;
        }
        else if (!actionName.equals(other.actionName))
            return false;
        if (categoryName == null) {
            if (other.categoryName != null)
                return false;
        }
        else if (!categoryName.equals(other.categoryName))
            return false;
        if (data == null) {
            if (other.data != null)
                return false;
        }
        else if (!data.equals(other.data))
            return false;
        return true;
    }
}
