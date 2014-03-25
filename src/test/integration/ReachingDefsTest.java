/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package test.integration;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.core.tests.callGraph.CallGraphTestUtil;
import com.ibm.wala.core.tests.util.TestConstants;
import com.ibm.wala.dataflow.graph.BitVectorSolver;
import com.ibm.wala.examples.analysis.dataflow.ContextInsensitiveReachingDefs;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.cfg.BasicBlockInContext;
import com.ibm.wala.ipa.cfg.ExplodedInterproceduralCFG;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.analysis.IExplodedBasicBlock;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.config.FileOfClasses;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;

/**
 * Tests of various flow analysis engines.
 */
public class ReachingDefsTest extends TestCase {

  private static AnalysisScope scope;

  private static IClassHierarchy cha;

  // more aggressive exclusions to avoid library blowup
  // in interprocedural tests
  private static final String EXCLUSIONS = "java\\/awt\\/.*\n" + "javax\\/swing\\/.*\n" + "sun\\/awt\\/.*\n" + "sun\\/swing\\/.*\n"
      + "com\\/sun\\/.*\n" + "sun\\/.*\n" + "org\\/netbeans\\/.*\n" + "org\\/openide\\/.*\n" + "com\\/ibm\\/crypto\\/.*\n"
      + "com\\/ibm\\/security\\/.*\n" + "org\\/apache\\/xerces\\/.*\n" + "java\\/security\\/.*\n" + "";

  @BeforeClass
  public static void beforeClass() throws Exception {

    scope = AnalysisScopeReader.readJavaScope(TestConstants.WALA_TESTDATA, null, ReachingDefsTest.class.getClassLoader());

    scope.setExclusions(new FileOfClasses(new ByteArrayInputStream(EXCLUSIONS.getBytes("UTF-8"))));
    try {
      cha = ClassHierarchy.make(scope);
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

  
  @Test
  public void testScratch() throws IllegalArgumentException, CallGraphBuilderCancelException, InvalidClassFileException {
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        "Ltest/Scratch");
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);

    CallGraphBuilder builder = Util.makeZeroOneCFABuilder(options, new AnalysisCache(), cha, scope);
    CallGraph cg = builder.makeCallGraph(options, null);
    ExplodedInterproceduralCFG icfg = ExplodedInterproceduralCFG.make(cg);
    ContextInsensitiveReachingDefs reachingDefs = new ContextInsensitiveReachingDefs(icfg, cha);
    BitVectorSolver<BasicBlockInContext<IExplodedBasicBlock>> solver = reachingDefs.analyze();
    for (BasicBlockInContext<IExplodedBasicBlock> bb : icfg) {
      if (bb.getNode().toString().contains("main")) {
        IExplodedBasicBlock delegate = bb.getDelegate();
        // if (delegate.getNumber() == 4) {
        IntSet solution = solver.getOut(bb).getValue();
        IBytecodeMethod bbMeth = (IBytecodeMethod) bb.getMethod();
        int fst = bb.getFirstInstructionIndex();
        int last = bb.getLastInstructionIndex();
        String sig = bbMeth.getSignature();
        StringBuilder sb = new StringBuilder();
        sb.append(delegate.getLastInstruction() + "\n");
        if (bb.isEntryBlock()) {
          sb.append("ENTRY: ");
        }
        if (bb.isExitBlock()) {
          sb.append("EXIT: ");
        }
        sb.append(sig);        
        if (fst >= 0 && last >= 0) {
          int fstLine = bbMeth.getLineNumber(bbMeth.getBytecodeIndex(fst));
          int lastLine = bbMeth.getLineNumber(bbMeth.getBytecodeIndex(last));
          sb.append(" lines: " + fstLine + "-" + lastLine);
        }
        
        System.out.println(sb.toString());
        
        IntIterator intIterator = solution.intIterator();
        List<Pair<CGNode, Integer>> applicationDefs = new ArrayList<Pair<CGNode, Integer>>();
        while (intIterator.hasNext()) {
          int next = intIterator.next();
          final Pair<CGNode, Integer> def = reachingDefs.getNodeAndInstrForNumber(next);
          if (def.fst.getMethod().getDeclaringClass().getClassLoader().getReference().equals(ClassLoaderReference.Application)) {
            // System.out.println(def);
            applicationDefs.add(def);
            // System.out.println("IR:\n" + def.fst.getIR());
            IBytecodeMethod method = (IBytecodeMethod) def.fst.getIR().getMethod();

            System.out.println("\t" + method.getSignature() + " line: " + method.getLineNumber(method.getBytecodeIndex(def.snd)));

          }
        }
        // Assert.assertEquals(2, applicationDefs.size());
        // }

      }
    }
  }
  
  @Test
  public void testContextInsensitive() throws IllegalArgumentException, CallGraphBuilderCancelException, InvalidClassFileException {
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        "Ldataflow/StaticDataflow");
    AnalysisOptions options =  new AnalysisOptions(scope, entrypoints);

    CallGraphBuilder builder = Util.makeZeroOneCFABuilder(options, new AnalysisCache(), cha, scope);
    CallGraph cg = builder.makeCallGraph(options, null);
    ExplodedInterproceduralCFG icfg = ExplodedInterproceduralCFG.make(cg);
    ContextInsensitiveReachingDefs reachingDefs = new ContextInsensitiveReachingDefs(icfg, cha);
    BitVectorSolver<BasicBlockInContext<IExplodedBasicBlock>> solver = reachingDefs.analyze();
    for (BasicBlockInContext<IExplodedBasicBlock> bb : icfg) {
      if (bb.getNode().toString().contains("main")) {
        IExplodedBasicBlock delegate = bb.getDelegate();
        // if (delegate.getNumber() == 4) {
        IntSet solution = solver.getOut(bb).getValue();
        IBytecodeMethod bbMeth = (IBytecodeMethod) bb.getMethod();
        int fst = bb.getFirstInstructionIndex();
        int last = bb.getLastInstructionIndex();
        String sig = bbMeth.getSignature();
        StringBuilder sb = new StringBuilder();
        sb.append(delegate.getLastInstruction() + "\n");
        if (bb.isEntryBlock()) {
          sb.append("ENTRY: ");
        }
        if (bb.isExitBlock()) {
          sb.append("EXIT: ");
        }
        sb.append(sig);        
        if (fst >= 0 && last >= 0) {
          int fstLine = bbMeth.getLineNumber(bbMeth.getBytecodeIndex(fst));
          int lastLine = bbMeth.getLineNumber(bbMeth.getBytecodeIndex(last));
          sb.append(" lines: " + fstLine + "-" + lastLine);
        }
        
        System.out.println(sb.toString());
        
        IntIterator intIterator = solution.intIterator();
        List<Pair<CGNode, Integer>> applicationDefs = new ArrayList<Pair<CGNode, Integer>>();
        while (intIterator.hasNext()) {
          int next = intIterator.next();
          final Pair<CGNode, Integer> def = reachingDefs.getNodeAndInstrForNumber(next);
          if (def.fst.getMethod().getDeclaringClass().getClassLoader().getReference().equals(ClassLoaderReference.Application)) {
            // System.out.println(def);
            applicationDefs.add(def);
            // System.out.println("IR:\n" + def.fst.getIR());
            IBytecodeMethod method = (IBytecodeMethod) def.fst.getIR().getMethod();

            System.out.println("\t" + method.getSignature() + " line: " + method.getLineNumber(method.getBytecodeIndex(def.snd)));

          }
        }
        // Assert.assertEquals(2, applicationDefs.size());
        // }

      }
    }
  }
}
