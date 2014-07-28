package android.intent.model;

import analysis.string.AbstractString;

public class AbstractComponentName {
    private AbstractString packageName;
    private AbstractString className;

    /**
     * Create an abstract ComponentName object that represents zero or more concrete (runtime) objects
     *
     * @param packageName
     * @param className
     */
    public AbstractComponentName(AbstractString packageName, AbstractString className) {
        assert className != null;
        assert packageName != null;
        this.className = className;
        this.packageName = packageName;
    }

    /**
     * Record a new possible package and class name (from an init method)
     *
     * @param pkgName package name to add
     * @param clsName class name to add
     * @return new AbstractComponentName if this object changed (otherwise returss this)
     */
    public AbstractComponentName join(AbstractString pkgName, AbstractString clsName) {
        AbstractString tempPkg = AbstractString.join(this.packageName, pkgName);
        AbstractString tempClass = AbstractString.join(this.className, clsName);
        if (this.packageName.equals(tempPkg) && this.className.equals(tempClass)) {
            return this;
        }
        return new AbstractComponentName(tempPkg, tempClass);
    }

    /**
     * Record a new possible package and class name (from an init method)
     *
     * @param pkgName package name to add
     * @param clsName class name to add
     * @return new AbstractComponentName if this object changed (otherwise returss this)
     */
    public AbstractComponentName join(AbstractComponentName otherComponentName) {
        return join(otherComponentName.getPackageName(), otherComponentName.getPackageName());
    }

    public AbstractString getClassName() {
        return className;
    }

    public AbstractString getPackageName() {
        return packageName;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((className == null) ? 0 : className.hashCode());
        result = prime * result + ((packageName == null) ? 0 : packageName.hashCode());
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
        if (getClass() != obj.getClass()) {
            return false;
        }
        AbstractComponentName other = (AbstractComponentName) obj;
        if (className == null) {
            if (other.className != null) {
                return false;
            }
        }
        else if (!className.equals(other.className)) {
            return false;
        }
        if (packageName == null) {
            if (other.packageName != null) {
                return false;
            }
        }
        else if (!packageName.equals(other.packageName)) {
            return false;
        }
        return true;
    }
}
