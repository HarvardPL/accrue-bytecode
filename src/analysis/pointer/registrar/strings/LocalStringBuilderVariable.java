package analysis.pointer.registrar.strings;

import java.util.Set;

import analysis.AnalysisUtil;
import analysis.dataflow.flowsensitizer.StringBuilderLocation;
import analysis.pointer.graph.strings.StringLikeLocationReplica;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

public class LocalStringBuilderVariable implements StringLikeVariable {
    private final IMethod m;
    private final int varNum;
    private final StringBuilderLocation objectSet;
    private final TypeReference klass;

    /* Factory Methods */

    public static LocalStringBuilderVariable makeStringBuilder(IMethod m, int varNum,
                                                               StringBuilderLocation s) {
        return new LocalStringBuilderVariable(m, varNum, s, TypeReference.JavaLangStringBuilder);
    }

    public static StringLikeVariable makeNull(IMethod m, int varNum) {
        return new LocalStringBuilderVariable(m, varNum, null, TypeReference.Null);
    }

    /* Constructor */

    private LocalStringBuilderVariable(IMethod m, int varNum, StringBuilderLocation s,
                                       TypeReference klass) {
        this.m = m;
        this.varNum = varNum;
        this.objectSet = s;
        this.klass = klass;
    }

    /* Logic */

    @Override
    public Set<StringLikeLocationReplica> getStringLocationReplicas(Context context) {
        return this.objectSet == null ? AnalysisUtil.<StringLikeLocationReplica> createConcurrentSet()
                : this.objectSet.getStringLocationReplicas(context);
    }

    @Override
    public TypeReference getExpectedType() {
        return this.klass;
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

    public StringBuilderLocation getObjectSet() {
        return this.objectSet;
    }

    @Override
    public boolean isStringBuilder() {
        return this.klass.equals(TypeReference.JavaLangStringBuilder);
    }

    @Override
    public boolean isNull() {
        return this.klass.equals(TypeReference.Null);
    }

    @Override
    public boolean isString() {
        return false;
    }

    /*
     * AUTOGENERATED STUFF
     *
     * Be sure to regenerate these (using Eclipse) if you change the number of fields in this class
     *
     * DEFINTIELY DONT CHANGE ANYTHING
     */

    @Override
    public String toString() {
        return "(LSV " + varNum + "_" + objectSet + " " + m.getDeclaringClass().getName() + "." + m.getName() + ")";
        //        return "LocalStringVariable ["/* + "m=" + m*/+ ", varNum=" + varNum + ", sensitizingSubscript="
        //                + sensitizingSubscript
        //                + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((klass == null) ? 0 : klass.hashCode());
        result = prime * result + ((m == null) ? 0 : m.hashCode());
        result = prime * result + ((objectSet == null) ? 0 : objectSet.hashCode());
        result = prime * result + varNum;
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
        if (!(obj instanceof LocalStringBuilderVariable)) {
            return false;
        }
        LocalStringBuilderVariable other = (LocalStringBuilderVariable) obj;
        if (klass == null) {
            if (other.klass != null) {
                return false;
            }
        }
        else if (!klass.equals(other.klass)) {
            return false;
        }
        if (m == null) {
            if (other.m != null) {
                return false;
            }
        }
        else if (!m.equals(other.m)) {
            return false;
        }
        if (objectSet == null) {
            if (other.objectSet != null) {
                return false;
            }
        }
        else if (!objectSet.equals(other.objectSet)) {
            return false;
        }
        if (varNum != other.varNum) {
            return false;
        }
        return true;
    }

}
