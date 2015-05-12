package analysis.pointer.registrar.strings;

import analysis.dataflow.flowsensitizer.StringBuilderLocation;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;

public class StringBuilderVariableFactory {
    /* Factory Methods */

    public static StringLikeVariable makeLocalStringBuilder(IMethod m, int varNum,
                                                        StringBuilderLocation s) {
        return LocalStringBuilderVariable.makeStringBuilder(m, varNum, s);
    }

    public static StringLikeVariable makeLocalNull(IMethod method, int varNum) {
        return LocalStringBuilderVariable.makeNull(method, varNum);
    }

    public static StringLikeVariable makeField(IField f) {
        return FieldStringBuilderVariable.make(f);
    }

}
