package android;

import java.util.LinkedHashSet;
import java.util.Set;

import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.strings.Atom;

public class AndroidConstants {

    /**
     * Class loader to use when making new types to find classes for
     */
    private static final ClassLoaderReference CLASS_LOADER = ClassLoaderReference.Application;

    /**
     * Activity class
     */
    public static final IClass ACTIVITY_CLASS;

    /**
     * Class for android.content.Intent
     */
    public static final IClass INTENT_CLASS;

    /**
     * There are a few "startActivity" methods, but this is the only one that can call into the Android system to
     * actually start the activity. The others are just convenience methods that call this method.
     * <p>
     * public void android.app.Activity.startActivityForResult(Intent intent, int requestCode, Bundle options)
     */
    public static final IMethod ACTIVITY_START_ACTIVITY_FOR_RESULT_METHOD;

    /**
     * Set of methods that perform some reflective operation and needs special handling
     */
    public static final Set<IMethod> INTERESTING_METHODS = new LinkedHashSet<>();

    public static final Set<IField> INTENT_FIELDS = new LinkedHashSet<>();

    static {
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();

        // Initialize ACTIVITY_CLASS
        TypeReference activityType = TypeReference.findOrCreate(CLASS_LOADER, "Landroid/app/Activity");
        ACTIVITY_CLASS = cha.lookupClass(activityType);

        // Initialize INTENT_CLASS
        TypeReference intentType = TypeReference.findOrCreate(CLASS_LOADER, "Landroid/content/Intent");
        INTENT_CLASS = cha.lookupClass(intentType);

        //        private String mAction;
        //        private Uri mData;
        //        private String mType;
        //        private String mPackage;
        //        private ComponentName mComponent;
        //        private int mFlags;
        //        private ArraySet<String> mCategories;
        //        private Bundle mExtras;
        //        private Rect mSourceBounds;
        //        private Intent mSelector;
        //        private ClipData mClipData;

        // Name of action
        FieldReference mAction = FieldReference.findOrCreate(intentType,
                                                             Atom.findOrCreateAsciiAtom("mAction"),
                                                             TypeReference.JavaLangString);
        INTENT_FIELDS.add(cha.resolveField(INTENT_CLASS, mAction));

        // URI of data
        TypeReference uriType = TypeReference.findOrCreate(CLASS_LOADER, "Landroid/net/Uri");
        FieldReference mData = FieldReference.findOrCreate(intentType, Atom.findOrCreateAsciiAtom("mData"), uriType);
        INTENT_FIELDS.add(cha.resolveField(INTENT_CLASS, mData));

        // Type of data
        FieldReference mType = FieldReference.findOrCreate(intentType,
                                                           Atom.findOrCreateAsciiAtom("mType"),
                                                           TypeReference.JavaLangString);
        INTENT_FIELDS.add(cha.resolveField(INTENT_CLASS, mType));

        // Name of package this is limited to
        FieldReference mPackage = FieldReference.findOrCreate(intentType,
                                                              Atom.findOrCreateAsciiAtom("mPackage"),
                                                              TypeReference.JavaLangString);
        INTENT_FIELDS.add(cha.resolveField(INTENT_CLASS, mPackage));

        // Explicit component name
        TypeReference componentNameType = TypeReference.findOrCreate(CLASS_LOADER, "Landroid/content/ComponentName");
        FieldReference mComponent = FieldReference.findOrCreate(intentType,
                                                                Atom.findOrCreateAsciiAtom("mComponent"),
                                                                componentNameType);
        INTENT_FIELDS.add(cha.resolveField(INTENT_CLASS, mComponent));

        // Set of categories
        TypeReference arraySetType = TypeReference.findOrCreate(CLASS_LOADER, "Landroid/util/ArraySet");
        FieldReference mCategories = FieldReference.findOrCreate(intentType,
                                                                 Atom.findOrCreateAsciiAtom("mPackage"),
                                                                 arraySetType);
        INTENT_FIELDS.add(cha.resolveField(INTENT_CLASS, mCategories));

        // Intent used to match in addition to outer intent
        FieldReference mSelector = FieldReference.findOrCreate(intentType,
                                                               Atom.findOrCreateAsciiAtom("mSelector"),
                                                               intentType);
        INTENT_FIELDS.add(cha.resolveField(INTENT_CLASS, mSelector));

        // Initialize START_ACTIVITY_FOR_RESULT_METHOD
        TypeName bundleName = TypeName.findOrCreate("Landroid/os/Bundle");
        TypeName intentName = TypeName.findOrCreate("Landroid/content/Intent");
        TypeName[] args = new TypeName[] { intentName, TypeReference.IntName, bundleName };

        Atom name = Atom.findOrCreateAsciiAtom("startActivityForResult");
        Descriptor descriptor = Descriptor.findOrCreate(args, TypeReference.VoidName);
        MethodReference m = MethodReference.findOrCreate(activityType, name, descriptor);
        ACTIVITY_START_ACTIVITY_FOR_RESULT_METHOD = cha.resolveMethod(m);

        INTERESTING_METHODS.add(ACTIVITY_START_ACTIVITY_FOR_RESULT_METHOD);
    }
}
