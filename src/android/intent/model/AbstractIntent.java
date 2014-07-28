package android.intent.model;

import java.util.Set;

import analysis.string.AbstractString;

/**
 * An android.content.Intent is used to start an Activity (or other component). These are filtered in order to determine
 * which class is initialized by the framework. The filter is based on the Action, Data, Type, Package, Component Name,
 * and Categories defined by the Intent and declared for the Activity in the applications AndroidManifest.xml
 */
public class AbstractIntent {
    // non-static fields in android.content.Intent
    //    private String mAction;
    //    private Uri mData;
    //    private String mType;
    //    private String mPackage;
    //    private ComponentName mComponent;
    //    private int mFlags;
    //    private ArraySet<String> mCategories;
    //    private Bundle mExtras;
    //    private Rect mSourceBounds;
    //    private Intent mSelector;
    //    private ClipData mClipData;

    private AbstractString action;
    private AbstractURI dataURI;
    private AbstractString type;
    private AbstractComponentName componentName;
    private AbstractString packageName;
    private Set<AbstractString> categories;
}
