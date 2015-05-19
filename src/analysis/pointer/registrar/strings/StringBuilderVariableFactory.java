package analysis.pointer.registrar.strings;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;

public class StringBuilderVariableFactory {
    /* Factory Methods */

    public static StringLikeVariable makeLocalNull(IMethod method, int varNum) {
        return NullStringBuilderVariable.make(method, varNum);
    }

    public static StringLikeVariable makeField(IField f) {
        return FieldStringBuilderVariable.make(f);
    }

}
