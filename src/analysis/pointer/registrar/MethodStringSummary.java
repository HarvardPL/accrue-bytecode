package analysis.pointer.registrar;

import java.util.ArrayList;
import java.util.List;

import analysis.StringAndReflectiveUtil;
import analysis.pointer.registrar.strings.StringVariable;
import analysis.pointer.registrar.strings.StringVariableFactory;

import com.ibm.wala.classLoader.IClass;
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

    public static MethodStringSummary makeNative(IMethod method) {
        StringVariable ret;
        if (StringAndReflectiveUtil.JavaLangStringIClass.equals(method.getReturnType())) {
            ret = StringVariableFactory.makeMethodReturnString(method);
        }
        else if (StringAndReflectiveUtil.JavaLangStringBuilderIClass.equals(method.getReturnType())) {
            ret = StringVariableFactory.makeMethodReturnStringBuilder(method);
        }
        else {
            ret = null;
        }

        List<StringVariable> formals = new ArrayList<>(method.getNumberOfParameters());
        for (int i = 0; i < method.getNumberOfParameters(); ++i) {
            IClass parameterType = StringAndReflectiveUtil.typeReferenceToIClass(method.getParameterType(i));
            if (StringAndReflectiveUtil.JavaLangStringIClass.equals(parameterType)) {
                formals.add(StringVariableFactory.makeNativeParameterString(method, i));
            }
            else if (StringAndReflectiveUtil.JavaLangStringBuilderIClass.equals(parameterType)) {
                formals.add(StringVariableFactory.makeNativeParameterStringBuilder(method, i));
            } else {
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

    @Override
    public String toString() {
        return "MethodStringSummary [ret=" + ret + ", formals=" + formals + "]";
    }

}
