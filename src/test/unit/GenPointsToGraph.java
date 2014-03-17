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
package test.unit;

import java.io.File;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import pointer.analyses.CallSiteSensitive;
import pointer.analyses.HeapAbstractionFactory;
import pointer.engine.PointsToAnalysis;
import pointer.engine.PointsToAnalysisSingleThreaded;
import pointer.graph.PointsToGraph;
import pointer.graph.PointsToGraphNode;
import pointer.statements.PointsToStatement;
import pointer.statements.StatementRegistrar;
import pointer.statements.StatementRegistrationPass;

import com.ibm.wala.core.tests.util.WalaTestCase;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.config.AnalysisScopeReader;

/**
 * Generate a points-to graph
 */
public class GenPointsToGraph extends WalaTestCase {

	private static AnalysisScope scope;

	private static IClassHierarchy cha;

    private static AnalysisCache cache;
    
    private static AnalysisOptions options;

	@BeforeClass
	public static void beforeClass() throws Exception {

		String classPath = "/Users/mu/Documents/workspace/WALA/walaAnalysis/bin";
		File exclusions = new File("/Users/mu/Documents/workspace/WALA/walaAnalysis/data/Exclusions.txt");

		scope = AnalysisScopeReader.makePrimordialScope(exclusions);
		AnalysisScopeReader.addClassPathToScope(classPath, scope,
				ClassLoaderReference.Application);

		try {
			long start = System.currentTimeMillis();
			cha = ClassHierarchy.make(scope);
			System.out.println(cha.getNumberOfClasses()
					+ " classes loaded. It took "
					+ (System.currentTimeMillis() - start) + "ms");
		} catch (ClassHierarchyException e) {
			throw new Exception(e);
		}
		cache = new AnalysisCache();
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
		cache = null;
		options = null;
	}

	@Test
	public void testScratch() {
	    
		Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util
				.makeMainEntrypoints(scope, cha, "Ltest/Scratch");
		System.out.println("Made entry points");
        options = new AnalysisOptions(scope, entrypoints);

		StatementRegistrationPass pass = new StatementRegistrationPass(cha, cache, options);
		pass.run();
		System.out.println("Registered statements: " + pass.getRegistrar().getAllStatements().size());
		for (PointsToStatement s : pass.getRegistrar().getAllStatements()) {
		    System.out.println("\t" + s + " (" + s.getClass().getSimpleName() +")");
		}
		StatementRegistrar registrar = pass.getRegistrar();
		
		HeapAbstractionFactory context = new CallSiteSensitive();
		PointsToAnalysis analysis = new PointsToAnalysisSingleThreaded(context, cha);
		PointsToGraph g = analysis.solve(registrar);
		g.dumpPointsToGraphToFile(false);
		System.out.println(g.getNodes().size() + " Nodes");
		int num = 0;
		for (PointsToGraphNode n : g.getNodes()) {
		    num += g.getPointsToSet(n).size();
		}
		System.out.println(num + " Edges");
		System.out.println(g.getAllHContexts().size() + " HContexts");
	}
}
