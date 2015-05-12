package analysis.pointer.registrar;

import java.util.ArrayList;
import java.util.List;

import analysis.StringAndReflectiveUtil;
import analysis.pointer.registrar.strings.StringLikeVariable;
import analysis.pointer.registrar.strings.StringVariableFactory;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.TypeReference;

public class MethodStringSummary {
    /* NB: Both ret and the elements of formals could be null indicating */
    /* that they are not String-like */
    private final StringLikeVariable ret;
    private final List<StringLikeVariable> formals;

    public static MethodStringSummary make(IMethod method, IR ir) {
        StringLikeVariable ret;
        TypeReference returnType = method.getReturnType();

        if (StringAndReflectiveUtil.isStringType(returnType)) {
            ret = StringVariableFactory.makeMethodReturnString(method);
        }
        else if (StringAndReflectiveUtil.isStringBuilderType(returnType)) {
            ret = null;
        }
        else {
            ret = null;
        }

        List<StringLikeVariable> formals = new ArrayList<>(method.getNumberOfParameters());
        for (int i = 0; i < method.getNumberOfParameters(); ++i) {
            TypeReference parameterType = method.getParameterType(i);
            if (StringAndReflectiveUtil.isStringType(parameterType)) {
                formals.add(StringVariableFactory.makeLocalString(method, ir.getParameter(i)));
            }
            else if (StringAndReflectiveUtil.isStringBuilderType(parameterType)) {
                formals.add(null);
            }
            else {
                formals.add(null);
            }
        }

        return new MethodStringSummary(ret, formals);
    }

    public static MethodStringSummary makeNative(IMethod method) {
        StringLikeVariable ret;
        if (StringAndReflectiveUtil.isStringType(method.getReturnType())) {
            ret = StringVariableFactory.makeMethodReturnString(method);
        }
        else if (StringAndReflectiveUtil.isStringBuilderType(method.getReturnType())) {
            ret = null;
        }
        else {
            ret = null;
        }

        List<StringLikeVariable> formals = new ArrayList<>(method.getNumberOfParameters());
        for (int i = 0; i < method.getNumberOfParameters(); ++i) {
            if (StringAndReflectiveUtil.isStringType(method.getParameterType(i))) {
                // XXX: Do I need this at all?
                formals.add(StringVariableFactory.makeNativeParameterString(method, i));
            }
            else if (StringAndReflectiveUtil.isStringBuilderType(method.getParameterType(i))) {
                formals.add(null);
            }
            else {
                formals.add(null);
            }
        }
        return new MethodStringSummary(ret, formals);
    }

    private MethodStringSummary(StringLikeVariable ret, List<StringLikeVariable> formals) {
        this.ret = ret;
        this.formals = formals;
    }

    public StringLikeVariable getRet() {
        return this.ret;
    }

    public List<StringLikeVariable> getFormals() {
        return this.formals;
    }

    @Override
    public String toString() {
        return "MethodStringSummary [ret=" + ret + ", formals=" + formals + "]";
    }

}
