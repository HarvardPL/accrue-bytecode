package android.manifest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import brut.androlib.AndrolibException;
import brut.androlib.ApkDecoder;
import brut.androlib.res.data.ResPackage;
import brut.androlib.res.data.ResTable;
import brut.androlib.res.decoder.AXmlResourceParser;
import brut.androlib.res.decoder.ResAttrDecoder;

public class ManifestFile {

    private final Map<String, Activity> activities = new LinkedHashMap<>();
    private final Set<String> permissions = new LinkedHashSet<>();
    private final Map<String, String> metaData = new LinkedHashMap<>();
    private String packageName;
    private final String fileName;
    private static final String NAME = "name";
    private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";
    private static final String PACKAGE = "package";
    private static final String VALUE = "value";

    public ManifestFile(String apkFileName) {
        this.fileName = apkFileName;
        ApkDecoder decoder = new ApkDecoder(new File(apkFileName));
        try {
            ResTable table = decoder.getResTable();
            try (ZipFile zip = new ZipFile(apkFileName)) {
                ZipEntry entry = zip.getEntry("AndroidManifest.xml");
                AXmlResourceParser p = new AXmlResourceParser();
                ResAttrDecoder attrDecoder = new ResAttrDecoder();
                attrDecoder.setCurrentPackage(new ResPackage(table, 0, null));
                p.setAttrDecoder(attrDecoder);
                try (InputStream in = zip.getInputStream(entry)) {
                    processManifest(p, in);
                }
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        catch (AndrolibException e) {
            throw new RuntimeException(e);
        }
    }

    private void processManifest(AXmlResourceParser p, InputStream in) {
        System.err.println("PROCESSING: " + fileName);
        int element;
        p.open(in);
        StringBuilder sb = new StringBuilder();
        Activity currentActivity = null;
        try {
            while ((element = p.next()) != XmlPullParser.END_DOCUMENT) {
                switch (element) {
                case XmlPullParser.START_DOCUMENT:
                    break;

                // To handle an opening tag we create a new node
                // and fetch the namespace and all attributes
                case XmlPullParser.START_TAG:
                    XMLTag tag = XMLTag.getTag(p.getName());
                    if (tag == null) {
                        break;
                    }
                    switch (tag) {
                    case ACTIVITY:
                        Map<String, String> attributes = new LinkedHashMap<>();
                        String activityName = null;
                        for (int i = 0; i < p.getAttributeCount(); i++) {
                            String attrName = p.getAttributeName(i);
                            attributes.put(attrName, p.getAttributeValue(i));
                            if (attrName.equals(NAME)) {
                                activityName = p.getAttributeValue(i);
                                System.err.println("ACTIVITY: " + activityName);
                            }
                        }
                        assert activityName != null : "No activity name found";
                        currentActivity = new Activity(attributes);
                        activities.put(activityName, currentActivity);
                        break;
                    case INTENT_FILTER:
                        Set<IntentFilter> filters = processIntentFilters(p);
                        assert currentActivity != null;
                        currentActivity.addAllFilters(filters);
                        System.err.println("INTENT for " + currentActivity.getAttribute(NAME));
                        break;
                    case MANIFEST:
                        for (int i = 0; i < p.getAttributeCount(); i++) {
                            if (p.getAttributeName(i).equals(PACKAGE)) {
                                packageName = p.getAttributeValue(i);
                                System.err.println("PACKAGE: " + packageName);
                                break;
                            }
                        }
                        assert packageName != null : "no package name found in manifest";
                        break;
                    case META_DATA:
                        String name = null;
                        String val = null;
                        for (int i = 0; i < p.getAttributeCount(); i++) {
                            String attrName = p.getAttributeName(i);
                            if (attrName.equals(NAME)) {
                                name = p.getAttributeValue(i);
                            }
                            if (attrName.equals(VALUE)) {
                                val = p.getAttributeValue(i);
                            }
                        }
                        assert name != null : "Null name in meta-data";
                        assert val != null : "Null value in meta-data";
                        metaData.put(name, val);
                        break;
                    case USES_PERMISSION:
                        String perm = p.getAttributeValue(ANDROID_NAMESPACE, NAME);
                        assert perm != null : "Null name in permission";
                        permissions.add(perm);
                        break;
                    case ACTION:
                    case CATEGORY:
                    case DATA:
                        throw new RuntimeException("Type " + tag
                                                        + " should only appear within an intent-filter declaration");
                    }

                case XmlPullParser.END_TAG:
                    // close activity
                    break;

                // case XmlPullParser.CDSECT:
                // case XmlPullParser.COMMENT:
                // case XmlPullParser.DOCDECL:
                // case XmlPullParser.ENTITY_REF:
                // case XmlPullParser.IGNORABLE_WHITESPACE:
                // case XmlPullParser.PROCESSING_INSTRUCTION:
                case XmlPullParser.TEXT:
                    System.err.println("Android docs do not contain tag " + element);
                    break;

                case XmlPullParser.END_DOCUMENT:
                    break;

                }
            }
            System.err.println(sb.toString());
        }
        catch (XmlPullParserException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Set<IntentFilter> processIntentFilters(AXmlResourceParser p) {
        // Need to create all combinatorial combinations of {category, action, data}
        Set<String> actions = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        Set<Map<String, String>> data = new LinkedHashSet<>();
        try {
            xml_parser_loop: while (true) {
                int element = p.next();
                switch (element) {
                case XmlPullParser.START_TAG:
                    XMLTag tag = XMLTag.getTag(p.getName());
                    switch (tag) {
                    case ACTION:
                        String name = p.getAttributeValue(ANDROID_NAMESPACE, NAME);
                        assert name != null;
                        actions.add(name);
                        break;
                    case CATEGORY:
                        name = p.getAttributeValue(ANDROID_NAMESPACE, NAME);
                        assert name != null;
                        categories.add(name);
                        break;
                    case DATA:
                        Map<String, String> dataAttrs = new LinkedHashMap<>();
                        for (int i = 0; i < p.getAttributeCount(); i++) {
                            String attrName = p.getAttributeName(i);
                            String attrValue = p.getAttributeValue(i);
                            dataAttrs.put(attrName, attrValue);
                        }
                        data.add(dataAttrs);
                        break;
                    case ACTIVITY:
                    case INTENT_FILTER:
                    case MANIFEST:
                    case META_DATA:
                    case USES_PERMISSION:
                        throw new RuntimeException("Unexpected");
                    }
                    break;
                case XmlPullParser.END_TAG:
                    if (XMLTag.getTag(p.getName()) == XMLTag.INTENT_FILTER) {
                        // Closing tag for the intent-filter declaration
                        break xml_parser_loop;
                    }
                    break;
                }
            }
        }
        catch (XmlPullParserException | IOException e) {
            throw new RuntimeException(e);
        }
        assert !actions.isEmpty() : "No action for intent filter";
        assert !categories.isEmpty() : "No category for intent filter";
        Set<IntentFilter> filters = new LinkedHashSet<>();
        // Construct all combinatorial combinations
        for (String action : actions) {
            for (String category : categories) {
                if (!data.isEmpty()) {
                    for (Map<String, String> dataMap : data) {
                        filters.add(new IntentFilter(action, category, dataMap));
                    }
                }
                else {
                    filters.add(new IntentFilter(action, category, null));
                }
            }
        }
        return filters;
    }

    public Activity getActivity(String name) {
        return activities.get(name);
    }

    public String getMetaDataValue(String key) {
        return metaData.get(key);
    }

    public String getPackageName() {
        return packageName;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    private static enum XMLTag {
        ACTIVITY("activity"), USES_PERMISSION("uses-permission"), INTENT_FILTER("intent-filter"), META_DATA("meta-data"), MANIFEST(
                                        "manifest"), ACTION("action"), CATEGORY("category"), DATA("data");

        private final String name;

        private XMLTag(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }

        public static XMLTag getTag(String tagName) {
            switch (tagName) {
            case "activity":
                return ACTIVITY;
            case "uses-permission":
                return USES_PERMISSION;
            case "intent-filter":
                return INTENT_FILTER;
            case "meta-data":
                return META_DATA;
            case "manifest":
                return MANIFEST;
            case "action":
                return ACTION;
            case "category":
                return CATEGORY;
            case "data":
                return DATA;
            }
            // not one of the standard tags
            System.out.println(tagName + " not handled");
            return null;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("package=" + packageName);
        sb.append("\npermissions=" + permissions);
        for (String actName : activities.keySet()) {
            sb.append("\n\nACTIVITY: " + actName);
            sb.append("\n" + activities.get(actName).toString());
        }
        return sb.toString();
    }

    public Set<String> getAllActivityNames() {
        return activities.keySet();
    }
}
