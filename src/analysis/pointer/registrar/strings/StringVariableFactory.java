package analysis.pointer.registrar.strings;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;

public class StringVariableFactory {
    /* Factory Methods */

    public static StringVariable makeLocal(IMethod m, int varNum, int sensitizingSubscript) {
        return LocalStringVariable.make(m, varNum, sensitizingSubscript);
    }

    public static StringVariable makeField(IField f) {
        return FieldStringVariable.make(f);
    }
}
