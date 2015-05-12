package analysis.pointer.registrar.strings;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;

public class StringVariableFactory {

    public static StringLikeVariable makeLocalString(IMethod method, Integer varNum) {
        return LocalStringVariable.make(method, varNum);
    }

    public static StringLikeVariable makeField(IField ifield) {
        return FieldStringVariable.make(ifield);
    }

    public static StringLikeVariable makeMethodReturnString(IMethod method) {
        return MethodReturnStringVariable.make(method);
    }

    public static StringLikeVariable makeNativeParameterString(IMethod method, int i) {
        return NativeParameterStringVariable.make(method, i);
    }

}
