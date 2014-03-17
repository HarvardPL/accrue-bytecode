package util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
import com.ibm.wala.shrikeBT.IInvokeInstruction.Dispatch;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAComparisonInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAConversionInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInstruction.IVisitor;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSAMonitorInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;

public class PrettyPrinter implements IVisitor {

    /**
     * Store the pretty printers here
     */
    private static final Map<IR, PrettyPrinter> PRINTERS = new HashMap<>();

    /**
     * Symbol table to use for printing
     */
    private final SymbolTable st;
    /**
     * IR associated with this pretty printer
     */
    private final IR ir;
    
    public static PrettyPrinter getPrinter(IR ir) {
        PrettyPrinter pp = PRINTERS.get(ir);
        if (pp != null) {
            return pp;
        }
        
        pp = new PrettyPrinter(ir);
        PRINTERS.put(ir, pp);
        return pp;
    }

    /**
     * Create a new pretty printer
     * 
     * @param ir
     *            IR the pretty printer is for
     */
    private PrettyPrinter(IR ir) {
        this.ir = ir;
        this.st = ir.getSymbolTable();
    }

    @Override
    public void visitGoto(SSAGotoInstruction instruction) {
        System.out.print(instruction.toString(st));
    }

    @Override
    public void visitArrayLoad(SSAArrayLoadInstruction instruction) {
        String ref = stringForValue(instruction.getArrayRef());
        String index = stringForValue(instruction.getIndex());
        String result = stringForValue(instruction.getDef());
        System.out.print(result + " = " + ref + "[" + index + "]");
    }

    @Override
    public void visitArrayStore(SSAArrayStoreInstruction instruction) {
        String ref = stringForValue(instruction.getArrayRef());
        String index = stringForValue(instruction.getIndex());
        String stored = stringForValue(instruction.getValue());
        System.out.print(ref + "[" + index + "]" + " = " + stored);
    }

    @Override
    public void visitBinaryOp(SSABinaryOpInstruction instruction) {
        // ADD, SUB, MUL, DIV, REM, AND, OR, XOR;
        // SHL, SHR, USHR;
        String opString = "";
        switch (instruction.getOperator().toString()) {
        case "add":
            opString = "+";
            break;
        case "sub":
            opString = "-";
            break;
        case "mul":
            opString = "*";
            break;
        case "div":
            opString = "/";
            break;
        case "rem":
            opString = "%";
            break;
        case "and":
            opString = "&";
            break;
        case "or":
            opString = "|";
            break;
        case "xor":
            opString = "^";
            break;
        case "SHL":
            opString = "<<";
            break;
        case "SHR":
            opString = ">>";
            break;
        case "USHR":
            opString = ">>>";
            break;
        default:
            throw new IllegalArgumentException("Urecognized binary operator " + instruction.getOperator() + " in "
                    + instruction.toString(st));
        }
        String res = stringForValue(instruction.getDef());
        String op1 = stringForValue(instruction.getUse(0));
        String op2 = stringForValue(instruction.getUse(1));

        System.out.print(res + " = " + op1 + " " + opString + " " + op2);
    }

    @Override
    public void visitUnaryOp(SSAUnaryOpInstruction instruction) {
        System.out.print(instruction.toString(st));
    }

    @Override
    public void visitConversion(SSAConversionInstruction instruction) {
        System.out.print(instruction.toString(st));
    }

    @Override
    public void visitComparison(SSAComparisonInstruction instruction) {
        System.out.print(instruction.toString(st));
    }

    @Override
    public void visitConditionalBranch(SSAConditionalBranchInstruction instruction) {
        StringBuffer sb = new StringBuffer();
        sb.append("if (" + stringForValue(instruction.getUse(0)));
        String opString = "";
        switch (instruction.getOperator().toString()) {
        case "eq":
            opString = "==";
            break;
        case "ne":
            opString = "!=";
            break;
        case "lt":
            opString = "<";
            break;
        case "ge":
            opString = ">=";
            break;
        case "gt":
            opString = ">";
            break;
        case "le":
            opString = "<=";
            break;
        default:
            throw new IllegalArgumentException("operator not found " + instruction.getOperator().toString() + " for "
                    + instruction);
        }
        sb.append(" " + opString + " ");
        sb.append(stringForValue(instruction.getUse(1)) + ")" + " then goto");
        System.out.print(sb.toString());
    }

    @Override
    public void visitSwitch(SSASwitchInstruction instruction) {
        System.out.print(instruction.toString(st));
    }

    @Override
    public void visitReturn(SSAReturnInstruction instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("return");

        int result = instruction.getResult();
        if (result != -1) {
            // -1 indicates a void return
            sb.append(" " + stringForValue(result));
        }

        System.out.print(sb.toString());
    }

    @Override
    public void visitGet(SSAGetInstruction instruction) {
        StringBuilder sb = new StringBuilder();

        if (instruction.hasDef()) {
            sb.append(stringForValue(instruction.getDef())).append(" = ");
        }

        String receiver = null;
        if (instruction.isStatic()) {
            receiver = parseType(instruction.getDeclaredField().getDeclaringClass().getName().toString());
        } else {
            receiver = stringForValue(instruction.getRef());
        }
        sb.append(receiver + ".");
        sb.append(instruction.getDeclaredField().getName());
        System.out.print(sb.toString());
    }

    @Override
    public void visitPut(SSAPutInstruction instruction) {
        StringBuilder sb = new StringBuilder();
        String receiver = null;
        if (instruction.isStatic()) {
            receiver = parseType(instruction.getDeclaredField().getDeclaringClass().getName().toString());
        } else {
            receiver = stringForValue(instruction.getRef());
        }
        sb.append(receiver + ".");
        sb.append(instruction.getDeclaredField().getName() + " = ");
        sb.append(stringForValue(instruction.getVal()));
        System.out.print(sb.toString());
    }

    @Override
    public void visitInvoke(SSAInvokeInstruction instruction) {
        StringBuilder sb = new StringBuilder();
        if (instruction.hasDef()) {
            sb.append(stringForValue(instruction.getDef())).append(" = ");
        }

        IInvokeInstruction.Dispatch type = (Dispatch) instruction.getCallSite().getInvocationCode();
        MethodReference mr = instruction.getDeclaredTarget();
        String receiver = null;
        switch (type) {
        case VIRTUAL:
            receiver = stringForValue(instruction.getReceiver());
            break;
        case INTERFACE:
            receiver = stringForValue(instruction.getReceiver());
            break;
        case SPECIAL:
            receiver = stringForValue(instruction.getReceiver());
            if (mr.getName().toString().equals("<init>")) {
                receiver = "(" + parseType(instruction.getDeclaredTarget().getDeclaringClass()) + ")" + receiver;
            }
            break;
        case STATIC:
            receiver = parseType(mr.getDeclaringClass().getName().toString());
            break;
        }

        sb.append(receiver);
        sb.append(".");
        sb.append(mr.getName());
        sb.append("(");
        if (mr.getNumberOfParameters() > 0) {
            if (type == Dispatch.STATIC) {
                sb.append(stringForValue(instruction.getUse(0)));
                for (int i = 1; i < mr.getNumberOfParameters(); i++) {
                    sb.append(", " + stringForValue(instruction.getUse(i)));
                }
            } else {
                // 0th use is "this" for virtual invocations
                sb.append(stringForValue(instruction.getUse(1)));
                for (int i = 2; i < mr.getNumberOfParameters(); i++) {
                    sb.append(", " + stringForValue(instruction.getUse(i)));
                }
            }
        }
        sb.append(")");
        sb.append(" throws " + stringForValue(instruction.getException()));
        System.out.print(sb.toString());
        // System.out.print("\t  " + instruction.toString(st));
    }

    @Override
    public void visitNew(SSANewInstruction instruction) {
        StringBuilder sb = new StringBuilder();
        if (instruction.hasDef()) {
            sb.append(stringForValue(instruction.getDef())).append(" = ");
        }
        sb.append("new ");
        sb.append(parseType(instruction.getConcreteType().getName().toString()));
        sb.append("(");
        if (instruction.getNumberOfUses() > 0) {
            sb.append(stringForValue(instruction.getUse(0)));
            for (int i = 1; i < instruction.getNumberOfUses(); i++) {
                sb.append(", " + stringForValue(instruction.getUse(i)));
            }
        }
        sb.append(")");
        System.out.print(sb.toString());
    }

    @Override
    public void visitArrayLength(SSAArrayLengthInstruction instruction) {
        System.out.print(stringForValue(instruction.getUse(0)) + ".length");
    }

    @Override
    public void visitThrow(SSAThrowInstruction instruction) {
        System.out.print(instruction.toString(st));
    }

    @Override
    public void visitMonitor(SSAMonitorInstruction instruction) {
        System.out.print(instruction.toString(st));
    }

    @Override
    public void visitCheckCast(SSACheckCastInstruction instruction) {
        int result = instruction.getResult();
        int value = instruction.getVal();
        StringBuffer s = new StringBuffer();
        s.append(stringForValue(result) + " = ");
        TypeReference[] types = instruction.getDeclaredResultTypes();
        if (types.length != 1) {
            System.err.println("More than one return type for a cast.");
        }
        s.append("(" + parseType(types[0]) + ")");
        s.append(stringForValue(value));
        System.out.print(s.toString());
    }

    @Override
    public void visitInstanceof(SSAInstanceofInstruction instruction) {
        System.out.print(instruction.toString(st));
    }

    @Override
    public void visitPhi(SSAPhiInstruction instruction) {
        StringBuilder s = new StringBuilder();
        s.append(stringForValue(instruction.getDef()) + " = ");
        s.append("phi" + "(");
        int uses = instruction.getNumberOfUses();
        for (int i = 0; i < uses - 1; i++){
            s.append(stringForValue(instruction.getUse(i)) + ", ");
        }
        s.append(stringForValue(instruction.getUse(uses - 1)) + ")");
        System.out.print(s.toString());
    }

    @Override
    public void visitPi(SSAPiInstruction instruction) {
        System.out.print(instruction.toString(st));
    }

    @Override
    public void visitGetCaughtException(SSAGetCaughtExceptionInstruction instruction) {
        System.out.print(instruction.toString(st));
    }

    @Override
    public void visitLoadMetadata(SSALoadMetadataInstruction instruction) {
        System.out.print(instruction.toString(st));
    }

    /**
     * C = char D = double F = float I = int J = long S = short Z = boolean
     * 
     * @param typeName
     * @return
     */
    private static String parseType(String typeName) {
        String arrayString = "";
        while (typeName.startsWith("[")) {
            arrayString += "[]";
            typeName = typeName.substring(1);
        }
        String baseName = "";
        switch (typeName.substring(0, 1)) {
        case "B":
            baseName = "byte";
            break;
        case "L":
            baseName = typeName.substring(1);
            break;
        case "C":
            baseName = "char";
            break;
        case "D":
            baseName = "double";
            break;
        case "F":
            baseName = "float";
            break;
        case "I":
            baseName = "int";
            break;
        case "J":
            baseName = "long";
            break;
        case "S":
            baseName = "short";
            break;
        case "Z":
            baseName = "boolean";
            break;
        case "V":
            baseName = "void";
            break;
        default:
            throw new RuntimeException(typeName.substring(0, 1) + " is an invalid type specifier.");
        }

        return baseName.replace("/", ".") + arrayString;
    }

    /**
     * Get a String for the given value number
     * 
     * @param valueNumber
     *            value number for the variable to get the string representation
     *            for
     * @return String for the given value
     */
    public String stringForValue(int valueNumber) {
        String ret = getActualName(valueNumber);
        if (ret != null) {
            return ret;
        }

        if (st.getValue(valueNumber) == null) {
            return "v" + valueNumber;
        } else {
            String v = st.getValue(valueNumber).toString();
            if (v.startsWith("#")) {
                // trim off the leading #
                v = v.substring(1);
            }
            if (st.isStringConstant(valueNumber)) {
                v = "\"" + v + "\"";
            }
            return v;
        }
    }

    /**
     * Print out the code for the given IR
     * 
     * @param prefix
     *            prefix printed before each instruction (e.g. "\t" to indent)
     * @param ir
     *            code to print
     */
    public static void printIR(String prefix, IR ir) {
        System.out.println(parseMethod(ir.getMethod().getReference()));
        PrettyPrinter pp = getPrinter(ir);
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            for (SSAInstruction i : bb) {
                System.out.print(prefix);
                i.visit(pp);
                System.out.println();
            }
        }
    }

    /**
     * Get a variable name if one exists
     * 
     * @param valNum
     *            number of the variable to get the name for
     * @return the name
     */
    private String getActualName(int valNum) {
        @SuppressWarnings("deprecation")
        int lastInstructionNum = ir.getInstructions().length - 1;
        String[] justForDebug = ir.getLocalNames(lastInstructionNum, valNum);
        if (justForDebug == null) {
            if (!ir.getMethod().isStatic() && valNum == 1) {
                return "this";
            }
            return null;
        }
        if (justForDebug.length == 0) {
            return null;
        }
        if (justForDebug.length > 1) {
            System.err.println("multiple names for " + valNum + " in " + parseMethod(ir.getMethod().getReference())
                    + ": " + Arrays.toString(justForDebug));
        }
        return justForDebug[0];
    }

    /**
     * Get a pretty string for the given type Reference
     * 
     * @param type
     *            type to get a string for
     * @return String for "type"
     */
    public static String parseType(TypeReference type) {
        return parseType(type.getName().toString());
    }

    /**
     * Get a pretty string for the given type reference, with no package names 
     * 
     * @param type
     *            type to get a string for
     * @return String for "type"
     */
    public static String parseTypeSimple(TypeReference type) {
        String fullType = parseType(type.getName().toString());
        String[] strings = fullType.split("\\.");
        return strings[strings.length - 1];
    }

    /**
     * Get a pretty String for the given method
     * 
     * @param m
     *            method to get a string for
     * @return String for "m"
     */
    public static String parseMethod(MethodReference m) {
        StringBuilder s = new StringBuilder();
        s.append(parseType(m.getDeclaringClass()));
        s.append("." + m.getName().toString());
        s.append("(");
        if (m.getNumberOfParameters() > 0) {
            Descriptor d = m.getDescriptor();
            TypeName[] n = d.getParameters();
            for (TypeName t : n) {
                s.append(parseType(t.toString()));
            }
        }
        s.append(")");
        return s.toString();
    }
    
    /**
     * Get the line number for the first instruction in the basic block
     * containing <code>i</code>. TODO this seems to be the best we can do
     * without an instruction index
     * 
     * @param i
     *            instruction to get the line number for
     * @return line number
     */
    public int getApproxLineNumber(SSAInstruction i) {
        ISSABasicBlock bb = ir.getBasicBlockForInstruction(i);
        int first = bb.getFirstInstructionIndex();
        if (ir.getMethod() instanceof IBytecodeMethod) {
            IBytecodeMethod method = (IBytecodeMethod)ir.getMethod();
            int bytecodeIndex = -1;
            try {
                bytecodeIndex = method.getBytecodeIndex(first);
            } catch (InvalidClassFileException e) {
                return -1;
            }
            int sourceLineNum = method.getLineNumber(bytecodeIndex);
            return sourceLineNum;
        }
        return -1;
    }
}
