package test.integration;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

import junit.framework.TestCase;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import util.print.PrettyPrinter;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.JavaLanguage.JavaInstructionFactory;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.cfg.BasicBlockInContext;
import com.ibm.wala.ipa.cfg.InterproceduralCFG;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.config.AnalysisScopeReader;

/**
 * Tests of various flow analysis engines.
 */
public class ListClasses extends TestCase {

	private static AnalysisScope scope;

	private static IClassHierarchy cha;

	@BeforeClass
	public static void beforeClass() throws Exception {

		String classPath = "/Users/mu/Documents/workspace/WALA/walaAnalysis/bin";

		scope = AnalysisScopeReader.makePrimordialScope(null);
		AnalysisScopeReader.addClassPathToScope(classPath, scope,
				ClassLoaderReference.Application);

		try {
			long start = System.currentTimeMillis();
			cha = ClassHierarchy.make(scope);
			System.out.println(cha.getNumberOfClasses()
					+ " classes loaded. It took "
					+ (System.currentTimeMillis() - start) + "ms");
		} catch (ClassHierarchyException e) {
			throw new Exception();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see junit.framework.TestCase#tearDown()
	 */
	@AfterClass
	public static void afterClass() throws Exception {
		scope = null;
		cha = null;
	}

	@SuppressWarnings("unused")
    @Test
	public void testScratch() throws IllegalArgumentException,
			CallGraphBuilderCancelException, InvalidClassFileException {

		PrettyPrinter pp;

		Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util
				.makeMainEntrypoints(scope, cha, "Ltest/Scratch");
		System.out.println("Made entry points");
		for (Entrypoint e : entrypoints) {
			System.out.println("EP: " + e.getMethod());
		}
		
		AnalysisOptions options =  new AnalysisOptions(scope, entrypoints);
		System.out.println("Made options");
		
		CallGraphBuilder builder = Util.makeZeroOneCFABuilder(options,
				new AnalysisCache(), cha, scope);
//		CallGraphBuilder builder = Util.makeRTABuilder(options,
//				new AnalysisCache(), cha, scope);
		
		System.out.println("Made CFG builder");
		
		long start = System.currentTimeMillis();
		CallGraph cg = builder.makeCallGraph(options, null);
		System.out.println("Made call graph " + "(" + builder.getClass() + ")" + ". It took " + (System.currentTimeMillis() - start) + "ms");
		
		
		InterproceduralCFG icfg = new InterproceduralCFG(cg);
		System.out.println("Made CFG");

		for (BasicBlockInContext<ISSABasicBlock> bb : icfg) {
			StringBuilder sb = new StringBuilder();
			if (bb.isEntryBlock()) {
				sb.append("ENTRY: ");
			} else if (bb.isExitBlock()) {
				sb.append("EXIT: ");
			}

			if (bb.getMethod() instanceof IBytecodeMethod) {

				IBytecodeMethod bbMeth = (IBytecodeMethod) bb.getMethod();

				int fst = bb.getFirstInstructionIndex();
				int last = bb.getLastInstructionIndex();

				if (fst >= 0 && last >= 0) {
					int fstLine = bbMeth.getLineNumber(bbMeth
							.getBytecodeIndex(fst));
					int lastLine = bbMeth.getLineNumber(bbMeth
							.getBytecodeIndex(last));
					sb.append(bb.getMethod().getSignature());
					sb.append(" lines: " + fstLine + "-" + lastLine);
				} else {
					sb.append(bbMeth.getSignature());
				}
			} else {
				sb.append(bb.getMethod().getClass());
			}

			System.out.println(sb.toString());
			
			if (bb.toString().contains("main(")) {
			    System.out.println("IR for main");
			    IR ir = bb.getNode().getIR();
			    Writer writer = new StringWriter();
			    PrettyPrinter.writeIR(ir, writer, "\t", "\n");
			    System.out.println(writer.toString());
			    try {
                    writer.close();
                } catch (IOException e1) {
                    throw new RuntimeException();
                }
			}
		}
	}

	String getClassName(SSAInstruction i) {
		Class<?> c = i.getClass();
		if (c.getEnclosingClass() == JavaInstructionFactory.class) {
			c = c.getSuperclass();
		}
		return c.getSimpleName();
	}
}
