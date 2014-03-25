package test;

import java.io.File;
import java.io.IOException;

import analysis.WalaAnalysisUtil;
import analysis.pointer.analyses.CallSiteSensitive;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysisSingleThreaded;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.StatementRegistrar;
import analysis.pointer.statements.StatementRegistrationPass;

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

public class TestMain {

    public static void main(String[] args) throws IOException, ClassHierarchyException {
        String entryPoint = args[0];
        int outputLevel = Integer.parseInt(args[1]);
        
        /********************************
         * Start of WALA set up code
         ********************************/
        String classPath = "/Users/mu/Documents/workspace/WALA/walaAnalysis/classes";
        File exclusions = new File("/Users/mu/Documents/workspace/WALA/walaAnalysis/data/Exclusions.txt");

        AnalysisScope scope = AnalysisScopeReader.makePrimordialScope(exclusions);
        AnalysisScopeReader.addClassPathToScope(classPath, scope, ClassLoaderReference.Application);

        long start = System.currentTimeMillis();
        IClassHierarchy cha = ClassHierarchy.make(scope);
        System.out.println(cha.getNumberOfClasses() + " classes loaded. It took "
                + (System.currentTimeMillis() - start) + "ms");

        AnalysisCache cache = new AnalysisCache();
        
        // Add L to the name to indicate that this is a class name
        Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util
                .makeMainEntrypoints(scope, cha, "L" + entryPoint.replace(".", "/"));
        AnalysisOptions options = new AnalysisOptions(scope, entrypoints);
        /********************************
         * End of WALA set up code
         ********************************/
        
        WalaAnalysisUtil util = new WalaAnalysisUtil(cha, cache, options);
        
        // Gather all the points-to statements
        StatementRegistrationPass pass = new StatementRegistrationPass(util);
        // Don't print anything
        StatementRegistrationPass.VERBOSE = outputLevel;
        pass.run();
        System.out.println("Registered statements: " + pass.getRegistrar().getAllStatements().size());
        if (outputLevel >= 2) {
            for (PointsToStatement s : pass.getRegistrar().getAllStatements()) {
                System.out.println("\t" + s + " (" + s.getClass().getSimpleName() + ")");
            }
        }
        StatementRegistrar registrar = pass.getRegistrar();
        
        HeapAbstractionFactory context = new CallSiteSensitive();
        PointsToAnalysis analysis = new PointsToAnalysisSingleThreaded(context, util);
        PointsToGraph g = analysis.solve(registrar);
        g.dumpPointsToGraphToFile(entryPoint, false);

        System.out.println(g.getNodes().size() + " Nodes");
        int num = 0;
        for (PointsToGraphNode n : g.getNodes()) {
            num += g.getPointsToSet(n).size();
        }
        System.out.println(num + " Edges");
        System.out.println(g.getAllHContexts().size() + " HContexts");
        
        int numNodes = 0;
        for (@SuppressWarnings("unused") CGNode n : g.getCallGraph()) {
            numNodes++;
        }
        System.out.println(numNodes + " CGNodes");
    }

}
