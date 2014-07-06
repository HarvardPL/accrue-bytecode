package android.manifest;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Activity {

    private final Map<String, String> attributes;
    private final Set<IntentFilter> filters = new HashSet<>();

    public Activity(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public String getAttribute(String key) {
        return this.attributes.get(key);
    }

    public boolean hasMatchingFilter(IntentFilter filter) {
        return this.filters.contains(filter);
    }

    public void addIntentFilter(IntentFilter filter) {
        this.filters.add(filter);
    }

    public void addAllFilters(Set<IntentFilter> filters) {
        this.filters.addAll(filters);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("attributes=");
        sb.append(this.attributes);
        sb.append("\nfilters={");
        if (!filters.isEmpty()) {
            sb.append("\n");
            for (IntentFilter f : this.filters) {
                sb.append(f);
                sb.append("\n");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
