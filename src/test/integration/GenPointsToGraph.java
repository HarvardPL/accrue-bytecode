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

import java.io.File;

import junit.framework.TestCase;
import analysis.WalaAnalysisUtil;
import analysis.pointer.analyses.CallSiteSensitive;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysisSingleThreaded;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.StatementRegistrationPass;
import analysis.pointer.statements.PointsToStatement;

import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.config.AnalysisScopeReader;

/**
 * Generate a points-to graph
 */
public class GenPointsToGraph extends TestCase {

    private static AnalysisScope scope;

    private static IClassHierarchy cha;

    private static AnalysisCache cache;

    private static AnalysisOptions options;

    @Override
    public void setUp() throws Exception {

        String classPath = "/Users/mu/Documents/workspace/WALA/walaAnalysis/classes";
        File exclusions = new File("/Users/mu/Documents/workspace/WALA/walaAnalysis/data/Exclusions.txt");

        scope = AnalysisScopeReader.makePrimordialScope(exclusions);
        AnalysisScopeReader.addClassPathToScope(classPath, scope, ClassLoaderReference.Application);

        try {
            long start = System.currentTimeMillis();
            cha = ClassHierarchy.make(scope);
            System.out.println(cha.getNumberOfClasses() + " classes loaded. It took "
                                            + (System.currentTimeMillis() - start) + "ms");
        } catch (ClassHierarchyException e) {
            throw new Exception(e);
        }
        cache = new AnalysisCache();
    }

    public static void testScratch() {
        Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
                                        "Ltest/Scratch");
        System.out.println("Made entry points");
        options = new AnalysisOptions(scope, entrypoints);

        WalaAnalysisUtil util = new WalaAnalysisUtil(cha, cache, options);
        StatementRegistrationPass pass = new StatementRegistrationPass(util);
        pass.run();
        System.out.println("Registered statements: " + pass.getRegistrar().getAllStatements().size());
        for (PointsToStatement s : pass.getRegistrar().getAllStatements()) {
            System.out.println("\t" + s + " (" + s.getClass().getSimpleName() + ")");
        }
        StatementRegistrar registrar = pass.getRegistrar();

        HeapAbstractionFactory context = new CallSiteSensitive();
        PointsToAnalysis analysis = new PointsToAnalysisSingleThreaded(context, util);
        PointsToGraph g = analysis.solve(registrar);
        g.dumpPointsToGraphToFile("pointsTo", false);

        System.out.println(g.getNodes().size() + " Nodes");
        int num = 0;
        for (PointsToGraphNode n : g.getNodes()) {
            num += g.getPointsToSet(n).size();
        }
        System.out.println(num + " Edges");
        System.out.println(g.getAllHContexts().size() + " HContexts");

        int numNodes = 0;
        for (@SuppressWarnings("unused")
        CGNode n : g.getCallGraph()) {
            numNodes++;
        }
        System.out.println(numNodes + " CGNodes");
    }
}
