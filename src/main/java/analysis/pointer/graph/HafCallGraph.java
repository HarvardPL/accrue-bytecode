package analysis.pointer.graph;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.ipa.callgraph.impl.ExplicitCallGraph;
import com.ibm.wala.util.CancelException;

/**
 * Call graph where new contexts are created using a
 * {@link HeapAbstractionFactory}
 */
public class HafCallGraph extends ExplicitCallGraph {

    /**
     * Heap abstraction factory defining how contexts are created
     */
    private final HeapAbstractionFactory haf;
    /**
     * WALA's fake root method that calls entry points
     */
    private final AbstractRootMethod fakeRoot;

    /**
     * Create and initialize a new call graph where contexts are created using
     * the given {@link HeapAbstractionFactory}
     *
     * @param haf
     *            Heap abstraction factory
     */
    public HafCallGraph(HeapAbstractionFactory haf) {
        super(AnalysisUtil.getClassHierarchy(), AnalysisUtil.getOptions(), AnalysisUtil.getCache());
        this.haf = haf;
        this.fakeRoot = AnalysisUtil.getFakeRoot();
        try {
            // Even though our analysis is context sensitive we use a context
            // insensitive "ContextInterpreter" this is correct because:

            // 1. The only thing the context is used for is to create different
            // IRs in different contexts, which is not necessary in our case

            // 2. When adding points-to statements to be analyzed we
            // do not have contexts yet as we haven't run the pointer analysis
            // so we use the context-insensitive IR to get the instructions. It
            // is important that the call graph use the same IR as the points-to
            // statement generation pass.
            this.setInterpreter(new AnalysisContextInterpreter());
            this.init();
        } catch (CancelException e) {
            throw new RuntimeException("WALA CancelException initializing call graph. " + e.getMessage());
        }
    }

    @Override
    protected CGNode makeFakeWorldClinitNode() {
        // We handle class initialization elsewhere.
        return null;
    }

    @Override
    protected CGNode makeFakeRootNode() throws CancelException {
        return findOrCreateNode(fakeRoot, haf.initialContext());
    }

    /**
     * Print the call graph in graphviz dot format to a file
     *
     * @param filename
     *            name of the file, the file is put in tests/filename.dot
     * @param addDate
     *            if true then the date will be added to the filename
     */
    public void dumpCallGraphToFile(String filename, boolean addDate) {
        String file = filename;
        if (addDate) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("-yyyy-MM-dd-HH_mm_ss");
            Date dateNow = new Date();
            String now = dateFormat.format(dateNow);
            file += now;
        }
        String fullFilename = file + ".dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            dumpCallGraph(out, this);
            System.err.println("\nDOT written to: " + fullFilename);
        }
        catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    /**
     * Print the call graph in graphviz dot format to a file
     *
     * @param filename name of the file, the file is put in tests/filename.dot
     * @param addDate if true then the date will be added to the filename
     */
    public static void dumpCallGraphToFile(String filename, boolean addDate, CallGraph cg) {
        String file = filename;
        if (addDate) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("-yyyy-MM-dd-HH_mm_ss");
            Date dateNow = new Date();
            String now = dateFormat.format(dateNow);
            file += now;
        }
        String fullFilename = file + ".dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            dumpCallGraph(out, cg);
            System.err.println("\nDOT written to: " + fullFilename);
        } catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    private static Writer dumpCallGraph(Writer writer, CallGraph cg) throws IOException {
        double spread = 1.0;
        writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
                                        + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n"
                                        + "edge [fontsize=10]" + ";\n");

        Map<String, Integer> dotToCount = new HashMap<>();
        Map<CGNode, String> n2s = new HashMap<>();

        // Need to differentiate between different nodes with the same string
        writer.write("/******************** NODES ********************/\n");
        for (CGNode n : cg) {
            String nStr = escape(PrettyPrinter.cgNodeString(n));
            Integer count = dotToCount.get(nStr);
            if (count == null) {
                dotToCount.put(nStr, 1);
            } else {
                dotToCount.put(nStr, count + 1);
                nStr += " (" + count + ")";
            }
            n2s.put(n, nStr);
            writer.write("\t\"" + nStr + "\";\n");
        }

        writer.write("/******************** EDGES ********************/\n");
        for (CGNode source : cg) {
            Iterator<CGNode> iter = cg.getSuccNodes(source);
            while (iter.hasNext()) {
                CGNode target = iter.next();
                writer.write("\t\"" + n2s.get(source) + "\" -> \"" + n2s.get(target) + "\";\n");
            }
        }

        writer.write("\n}\n");
        return writer;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
