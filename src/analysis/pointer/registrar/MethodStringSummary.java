package analysis.pointer.registrar;

import java.util.ArrayList;
import java.util.List;

import analysis.StringAndReflectiveUtil;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;

public class MethodStringSummary {
    /* NB: Both ret and the elements of formals could be null indicating */
    /* that they are not String-like */
    private final StringVariable ret;
    private final List<StringVariable> formals;

    public static MethodStringSummary make(FlowSensitiveStringVariableFactory stringVariableFactory, IMethod method,
                                           IR ir) {
        StringVariable ret;
        if (StringAndReflectiveUtil.isStringType(method.getReturnType())) {
            ret = stringVariableFactory.getOrCreateMethodReturn(method);
        }
        else {
            ret = null;
        }

        List<StringVariable> formals = new ArrayList<>(method.getNumberOfParameters());
        for (int i = 0; i < method.getNumberOfParameters(); ++i) {
            if (StringAndReflectiveUtil.isStringType(method.getParameterType(i))) {
                formals.add(stringVariableFactory.getOrCreateParamDef(ir.getParameter(i)));
            }
            else {
                formals.add(null);
            }
        }

        return new MethodStringSummary(ret, formals);
    }

    private MethodStringSummary(StringVariable ret, List<StringVariable> formals) {
        this.ret = ret;
        this.formals = formals;
    }

    public StringVariable getRet() {
        return this.ret;
    }

    public List<StringVariable> getFormals() {
        return this.formals;
    }
}
