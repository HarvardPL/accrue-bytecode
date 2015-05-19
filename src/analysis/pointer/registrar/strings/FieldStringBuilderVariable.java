package analysis.pointer.registrar.strings;

import java.util.Collections;
import java.util.Set;

import analysis.StringAndReflectiveUtil;
import analysis.pointer.graph.strings.EscapedStringBuilderLocationReplica;
import analysis.pointer.graph.strings.StringLikeLocationReplica;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;

public class FieldStringBuilderVariable implements StringLikeVariable {

    private final IField f;

    public static StringLikeVariable make(IField f) {
        return new FieldStringBuilderVariable(f);
    }

    private FieldStringBuilderVariable(IField f) {
        this.f = f;
    }

    @Override
    public Set<StringLikeLocationReplica> getStringLocationReplicas(Context context) {
        return Collections.singleton(EscapedStringBuilderLocationReplica.make());
    }

    @Override
    public TypeReference getExpectedType() {
        return StringAndReflectiveUtil.JavaLangStringTypeReference;
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

    @Override
    public boolean isStringBuilder() {
        return true;
    }

    @Override
    public boolean isString() {
        return false;
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((f == null) ? 0 : f.hashCode());
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
        if (!(obj instanceof FieldStringBuilderVariable)) {
            return false;
        }
        FieldStringBuilderVariable other = (FieldStringBuilderVariable) obj;
        if (f == null) {
            if (other.f != null) {
                return false;
            }
        }
        else if (!f.equals(other.f)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "FieldStringBuilderVariable [f=" + f + "]";
    }

}
