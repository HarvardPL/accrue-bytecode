package analysis.pointer.registrar.strings;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;

public class StringVariableFactory {
    /* Factory Methods */

    public static StringVariable makeLocalString(IMethod m, int varNum, int sensitizingSubscript) {
        return LocalStringVariable.makeString(m, varNum, sensitizingSubscript);
    }

    public static StringVariable makeLocalStringBuilder(IMethod m, int varNum, int sensitizingSubscript) {
        return LocalStringVariable.makeStringBuilder(m, varNum, sensitizingSubscript);
    }

    public static StringVariable makeMethodReturnString(IMethod m) {
        return MethodReturnStringVariable.makeString(m);
    }

    public static StringVariable makeMethodReturnStringBuilder(IMethod m) {
        return MethodReturnStringVariable.makeStringBuilder(m);
    }

    public static StringVariable makeField(IField f) {
        return FieldStringVariable.make(f);
    }

    public static StringVariable makeNativeParameterString(IMethod method, int i) {
        return NativeParameterStringVariable.makeString(method, i);
    }

    public static StringVariable makeNativeParameterStringBuilder(IMethod method, int i) {
        return NativeParameterStringVariable.makeStringBuilder(method, i);
    }

}
