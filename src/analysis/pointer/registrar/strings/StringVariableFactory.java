package analysis.pointer.registrar.strings;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;

public class StringVariableFactory {

    public static StringLikeVariable makeLocalString(IMethod method, Integer varNum) {
        return LocalStringVariable.makeString(method, varNum);
    }

    public static StringLikeVariable makeStringField(IField ifield) {
        return FieldStringVariable.makeString(ifield);
    }

    public static StringLikeVariable makeMethodReturnString(IMethod method) {
        return MethodReturnStringVariable.makeString(method);
    }

    public static StringLikeVariable makeMethodReturnObject(IMethod method) {
        return MethodReturnStringVariable.makeObject(method);
    }

    public static StringLikeVariable makeNativeParameterString(IMethod method, int i) {
        return NativeParameterStringVariable.make(method, i);
    }

    public static StringLikeVariable makeLocalObject(IMethod method, int varNum) {
        return LocalStringVariable.makeObject(method, varNum);
    }

    public static StringLikeVariable makeObjectField(IField ifield) {
        return FieldStringVariable.makeObject(ifield);
    }

}
