package util;

import com.ibm.wala.shrikeBT.IInvokeInstruction;
import com.ibm.wala.shrikeBT.IInvokeInstruction.Dispatch;
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
import com.ibm.wala.types.MethodReference;

public class PrettyPrinter implements IVisitor {
	
	private final SymbolTable st;
	private final boolean includeInstructionType;
	
	public PrettyPrinter(SymbolTable st) {
		this(st,false);
	}
	
	public PrettyPrinter(SymbolTable st, boolean includeInstructionType) {
		this.st = st;
		this.includeInstructionType = includeInstructionType;
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
		//ADD, SUB, MUL, DIV, REM, AND, OR, XOR;
		//SHL, SHR, USHR;
		String opString = "";
		switch(instruction.getOperator().toString()) {
		case "add": opString = "+"; break;
		case "sub": opString = "-"; break;
		case "mul":opString = "*"; break;
		case "div":opString = "/"; break;
		case "rem":opString = "%"; break;
		case "and":opString = "&"; break;
		case "or":opString = "|"; break;
		case "xor":opString = "^"; break;
		case "SHL":opString = "<<"; break;
		case "SHR":opString = ">>"; break;
		case "USHR":opString = ">>>"; break;
		default: throw new IllegalArgumentException("Urecognized binary operator " + instruction.getOperator() + " in " + instruction.toString(st));
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
	public void visitConditionalBranch(
			SSAConditionalBranchInstruction instruction) {
		StringBuffer sb = new StringBuffer();
		sb.append("if (" + stringForValue(instruction.getUse(0)));
		String opString = "";
		switch (instruction.getOperator().toString()) {
		case "eq": opString = "=="; break;
		case "ne": opString = "!="; break;
		case "lt": opString = "<"; break;
		case "ge": opString = ">="; break;
		case "gt": opString = ">"; break;
		case "le": opString = "<="; break;
		default: throw new IllegalArgumentException("operator not found " + instruction.getOperator().toString() + " for " + instruction);
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
		
		if (includeInstructionType) {
			if (instruction.isStatic()) {
				sb.append("getstatic ");
			} else {
				sb.append("getfield ");
			}
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
		if(includeInstructionType) {
			if (instruction.isStatic()) {
				sb.append("putstatic ");
			} else {
				sb.append("putfield ");
			}
		}
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
		if (includeInstructionType) {
			sb.append("invoke" + type.toString().toLowerCase() + " ");
		}
//		sb.append(parseType(mr.getReturnType().getName().toString()) + " "); // return type
		String receiver = null;
		switch(type) {
		case VIRTUAL : 
			receiver = stringForValue(instruction.getReceiver());
			break;
		case INTERFACE : 
			receiver = stringForValue(instruction.getReceiver());
			break;
		case SPECIAL : 
//			if (mr.getName().toString().equals("<init>")) {
//				receiver = "this";
//			} else {
				receiver = stringForValue(instruction.getReceiver());
//			}
			break;
		case STATIC : 
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
		System.out.print(sb.toString());
//		System.out.print("\t  " + instruction.toString(st));
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
		System.out.print(instruction.toString(st));
	}

	@Override
	public void visitInstanceof(SSAInstanceofInstruction instruction) {
		System.out.print(instruction.toString(st));
	}

	@Override
	public void visitPhi(SSAPhiInstruction instruction) {
		System.out.print(instruction.toString(st));
	}

	@Override
	public void visitPi(SSAPiInstruction instruction) {
		System.out.print(instruction.toString(st));
	}

	@Override
	public void visitGetCaughtException(
			SSAGetCaughtExceptionInstruction instruction) {
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
	private String parseType(String typeName) {
		String arrayString = "";
		while (typeName.startsWith("[")) {
			arrayString += "[]";
			typeName = typeName.substring(1);
		}
		String baseName = "";
		switch (typeName.substring(0, 1)) {
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
			throw new RuntimeException(typeName.substring(0, 1)
					+ " is an invalid type specifier.");
		}

		return baseName.replace("/", ".") + arrayString;
	}
	
	private String stringForValue(int valueNumber) {
		if (st.getValue(valueNumber) == null) {
//			if (valueNumber == 1 && notStatic) {
//				return "(this or super)";
//			}
			return "v" + valueNumber;
		} else {
			String v =  st.getValue(valueNumber).toString();
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
}
