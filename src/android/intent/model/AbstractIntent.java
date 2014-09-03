package android.intent.model;

import java.util.Collections;
import java.util.LinkedHashSet;
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

    public static final AbstractIntent ANY = new AbstractIntent(true);
    public static final AbstractIntent NONE = new AbstractIntent(false);
    private final AbstractString action;
    private final AbstractURI dataURI;
    private final AbstractString type;
    private final AbstractComponentName componentName;
    private final AbstractString packageName;
    private final Set<AbstractString> categories;

    /**
     *
     * @param isAny whether this intent corresponds to ANY intent or NONE (no possible intent)
     */
    private AbstractIntent(boolean isAny) {
        if (isAny) {
            action = AbstractString.ANY;
            dataURI = AbstractURI.ANY;
            type = AbstractString.ANY;
            componentName = AbstractComponentName.ANY;
            packageName = AbstractString.ANY;
            categories = Collections.singleton(AbstractString.ANY);
        }
        else {
            action = AbstractString.NONE;
            dataURI = AbstractURI.NONE;
            type = AbstractString.NONE;
            componentName = AbstractComponentName.NONE;
            packageName = AbstractString.NONE;
            categories = Collections.emptySet();
        }
    }

    private AbstractIntent(AbstractString action, AbstractURI dataURI, AbstractString type,
                           AbstractComponentName componentName, AbstractString packageName,
                           Set<AbstractString> categories) {
        this.action = action;
        this.dataURI = dataURI;
        this.type = type;
        this.componentName = componentName;
        this.packageName = packageName;
        this.categories = categories;
    }

    public static AbstractIntent join(AbstractIntent i1, AbstractIntent i2) {
        assert i1 != null;
        assert i2 != null;
        if (i1 == ANY || i2 == ANY) {
            return ANY;
        }
        if (i1 == NONE) {
            return i2;
        }
        if (i2 == NONE) {
            return i1;
        }

        AbstractString newAction = AbstractString.join(i1.getAction(), i2.getAction());
        AbstractURI newData = AbstractURI.join(i1.getDataURI(), i2.getDataURI());
        AbstractString newType = AbstractString.join(i1.getType(), i2.getType());
        AbstractComponentName newCN = AbstractComponentName.join(i1.getComponentName(), i2.getComponentName());
        AbstractString newPackageName = AbstractString.join(i1.getPackageName(), i2.getPackageName());
        Set<AbstractString> newCategories = new LinkedHashSet<>();
        newCategories.addAll(i1.getCategories());
        newCategories.addAll(i2.getCategories());
        return new AbstractIntent(newAction, newData, newType, newCN, newPackageName, newCategories);
    }

    public AbstractIntent joinAction(AbstractString action) {
        if (this == ANY) {
            return ANY;
        }
        AbstractString newAction = AbstractString.join(getAction(), action);
        return new AbstractIntent(newAction,
                                  getDataURI(),
                                  getType(),
                                  getComponentName(),
                                  getPackageName(),
                                  getCategories());
    }

    public AbstractIntent joinData(AbstractURI data) {
        if (this == ANY) {
            return ANY;
        }
        AbstractURI newData = AbstractURI.join(getDataURI(), data);
        return new AbstractIntent(getAction(),
                                  newData,
                                  getType(),
                                  getComponentName(),
                                  getPackageName(),
                                  getCategories());
    }

    public AbstractIntent joinType(AbstractString type) {
        if (this == ANY) {
            return ANY;
        }
        AbstractString newType = AbstractString.join(getType(), type);
        return new AbstractIntent(getAction(),
                                  getDataURI(),
                                  newType,
                                  getComponentName(),
                                  getPackageName(),
                                  getCategories());
    }

    public AbstractIntent joinComponentName(AbstractComponentName cn) {
        if (this == ANY) {
            return ANY;
        }
        AbstractComponentName newCN = AbstractComponentName.join(getComponentName(), cn);
        return new AbstractIntent(getAction(), getDataURI(), getType(), newCN, getPackageName(), getCategories());
    }

    public AbstractIntent joinPackageName(AbstractString packageName) {
        if (this == ANY) {
            return ANY;
        }
        AbstractString newPackageName = AbstractString.join(getPackageName(), packageName);
        return new AbstractIntent(getAction(),
                                  getDataURI(),
                                  getType(),
                                  getComponentName(),
                                  newPackageName,
                                  getCategories());
    }

    public AbstractIntent addCategory(AbstractString category) {
        if (this == ANY) {
            return ANY;
        }
        Set<AbstractString> newCategories = new LinkedHashSet<>();
        newCategories.addAll(getCategories());
        newCategories.add(category);
        return new AbstractIntent(getAction(),
                                  getDataURI(),
                                  getType(),
                                  getComponentName(),
                                  getPackageName(),
                                  newCategories);
    }

    public AbstractString getAction() {
        return action;
    }

    public AbstractURI getDataURI() {
        return dataURI;
    }

    public AbstractString getType() {
        return type;
    }

    public AbstractComponentName getComponentName() {
        return componentName;
    }

    public AbstractString getPackageName() {
        return packageName;
    }

    public Set<AbstractString> getCategories() {
        return categories;
    }

    @Override
    public String toString() {
        if (this == ANY) {
            return "AbstractIntent.ANY";
        }
        if (this == NONE) {
            return "AbstractIntent.NONE";
        }
        return "AbstractIntent [action=" + action + ", dataURI=" + dataURI + ", type=" + type + ", componentName="
                + componentName + ", packageName=" + packageName + ", categories=" + categories + "]";
    }
}
