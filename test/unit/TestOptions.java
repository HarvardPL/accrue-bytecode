package unit;

import junit.framework.TestCase;
import main.AccrueAnalysisMain;
import main.AccrueAnalysisOptions;
import analysis.pointer.analyses.CallSiteSensitive;
import analysis.pointer.analyses.CrossProduct;
import analysis.pointer.analyses.FullObjSensitive;
import analysis.pointer.analyses.StaticCallSiteSensitive;
import analysis.pointer.analyses.TypeSensitive;

import com.beust.jcommander.ParameterException;

/**
 * Test the setting of options for the main method in {@link AccrueAnalysisMain}
 */
public class TestOptions extends TestCase {

    public static void testOutputLevel() {
        String[] args = { "-output", "2" };
        AccrueAnalysisOptions o = AccrueAnalysisOptions.getOptions(args);
        assertEquals(2, o.getOutputLevel().intValue());

        String[] args2 = {};
        AccrueAnalysisOptions o2 = AccrueAnalysisOptions.getOptions(args2);
        assertEquals(0, o2.getOutputLevel().intValue());

        String[] args3 = { "-o", "4" };
        AccrueAnalysisOptions o3 = AccrueAnalysisOptions.getOptions(args3);
        assertEquals(4, o3.getOutputLevel().intValue());
    }

    public static void testFileOutput() {
        String[] args = { "-fileLevel", "2" };
        AccrueAnalysisOptions o = AccrueAnalysisOptions.getOptions(args);
        assertEquals(2, o.getFileLevel().intValue());

        String[] args2 = {};
        AccrueAnalysisOptions o2 = AccrueAnalysisOptions.getOptions(args2);
        assertEquals(0, o2.getFileLevel().intValue());
    }

    public static void testEntryPoint() {
        String entry = "test.Scratch";
        String[] args = { "-entry", entry, "-n", "pointsto" };
        AccrueAnalysisOptions o = AccrueAnalysisOptions.getOptions(args);
        assertEquals(entry, o.getEntryPoint());

        String entry2 = "test.Scratch";
        String[] args2 = { "-e", entry2, "-n", "pointsto" };
        AccrueAnalysisOptions o2 = AccrueAnalysisOptions.getOptions(args2);
        assertEquals(entry, o2.getEntryPoint());
    }

    public static void testNoEntryPoint() {
        String[] args = {};
        try {
            AccrueAnalysisOptions o = AccrueAnalysisOptions.getOptions(args);
            o.getEntryPoint();

        } catch (ParameterException e) {
            return;
        }
        fail("Should have thrown exception");
    }

    public static void testAnalyisName() {
        String name = "pointsto";
        String[] args = { "-analyisName", name };
        AccrueAnalysisOptions o = AccrueAnalysisOptions.getOptions(args);
        assertEquals(name, o.getAnalysisName());

        String[] args2 = { "-n", name };
        AccrueAnalysisOptions o2 = AccrueAnalysisOptions.getOptions(args2);
        assertEquals(name, o2.getAnalysisName());
    }

    public static void testNoAnalyisName() {
        String[] args = {};
        try {
            AccrueAnalysisOptions o = AccrueAnalysisOptions.getOptions(args);
            o.getAnalysisName();
        } catch (ParameterException e) {
            return;
        }
        fail("Should have thrown exception");
    }

    public static void testBadAnalyisName() {
        String name = "badTestName";
        String[] args = { "-analyisName", name };
        try {
            AccrueAnalysisOptions.getOptions(args);
        } catch (ParameterException e) {
            return;
        }
        fail("Should have thrown exception");
    }

    public static void testAnalysisClassPath() {
        String cp = "foo";
        String[] args = { "-analysisClassPath", cp };
        AccrueAnalysisOptions o = AccrueAnalysisOptions.getOptions(args);
        assertEquals(cp + ":classes/signatures", o.getAnalysisClassPath());

        String cp2 = "foo:classes/signatures";
        String[] args2 = { "-analysisClassPath", cp2 };
        AccrueAnalysisOptions o2 = AccrueAnalysisOptions.getOptions(args2);
        assertEquals(cp2, o2.getAnalysisClassPath());

        String cp3 = "foo:classes/signatures";
        String[] args3 = { "-cp", cp3 };
        AccrueAnalysisOptions o3 = AccrueAnalysisOptions.getOptions(args3);
        assertEquals(cp3, o3.getAnalysisClassPath());

        String[] args4 = {};
        AccrueAnalysisOptions o4 = AccrueAnalysisOptions.getOptions(args4);
        assertEquals("classes/test:classes/signatures", o4.getAnalysisClassPath());
    }

    public static void testUseage() {
        String[] args = { "-h" };
        AccrueAnalysisOptions o = AccrueAnalysisOptions.getOptions(args);
        assertEquals(true, o.shouldPrintUseage());

        String[] args2 = { "-useage" };
        AccrueAnalysisOptions o2 = AccrueAnalysisOptions.getOptions(args2);
        assertEquals(true, o2.shouldPrintUseage());

        String[] args3 = { "-help" };
        AccrueAnalysisOptions o3 = AccrueAnalysisOptions.getOptions(args3);
        assertEquals(true, o3.shouldPrintUseage());

        String[] args4 = { "--help" };
        AccrueAnalysisOptions o4 = AccrueAnalysisOptions.getOptions(args4);
        assertEquals(true, o4.shouldPrintUseage());
    }

    public static void testHaf2() {

        String[] args = { "-haf", "[scs(2) x type(2,1) x FilterStringBuilder]" };
        AccrueAnalysisOptions o = AccrueAnalysisOptions.getOptions(args);
        assertTrue(o.getHaf() instanceof CrossProduct);
        assertEquals("scs(2) x 2Type+1H x filter(2FullObjSens+1H, java.lang.AbstractStringBuilder)", o.getHaf()
                                                                                                       .toString());
    }

    /**
     * Test all the ways of specifying a HeapAbstractionFactory
     */
    public static void testHaf() {
        String[] args = {};
        AccrueAnalysisOptions o = AccrueAnalysisOptions.getOptions(args);
        assertTrue(o.getHaf() instanceof CrossProduct);
        assertEquals("2Type+1H x scs(2)", o.getHaf().toString());

        String[] args2 = { "-haf", "type" };
        AccrueAnalysisOptions o2 = AccrueAnalysisOptions.getOptions(args2);
        assertTrue(o2.getHaf() instanceof TypeSensitive);
        assertEquals("2Type+1H", o2.getHaf().toString());

        String[] args3 = { "-haf", "scs" };
        AccrueAnalysisOptions o3 = AccrueAnalysisOptions.getOptions(args3);
        assertTrue(o3.getHaf() instanceof StaticCallSiteSensitive);
        assertEquals("scs(2)", o3.getHaf().toString());

        String[] args4 = { "-haf", "cs" };
        AccrueAnalysisOptions o4 = AccrueAnalysisOptions.getOptions(args4);
        assertTrue(o4.getHaf() instanceof CallSiteSensitive);
        assertEquals("cs(2)", o4.getHaf().toString());

        String[] args5 = { "-haf", "full" };
        AccrueAnalysisOptions o5 = AccrueAnalysisOptions.getOptions(args5);
        assertTrue(o5.getHaf() instanceof FullObjSensitive);
        assertEquals("2FullObjSens+1H", o5.getHaf().toString());

        String[] args6 = { "-haf", "type(3,4)" };
        AccrueAnalysisOptions o6 = AccrueAnalysisOptions.getOptions(args6);
        assertTrue(o6.getHaf() instanceof TypeSensitive);
        assertEquals("3Type+4H", o6.getHaf().toString());

        String[] args7 = { "-haf", "scs(55)" };
        AccrueAnalysisOptions o7 = AccrueAnalysisOptions.getOptions(args7);
        assertTrue(o7.getHaf() instanceof StaticCallSiteSensitive);
        assertEquals("scs(55)", o7.getHaf().toString());

        String[] args8 = { "-haf", "cs(34)" };
        AccrueAnalysisOptions o8 = AccrueAnalysisOptions.getOptions(args8);
        assertTrue(o8.getHaf() instanceof CallSiteSensitive);
        assertEquals("cs(34)", o8.getHaf().toString());

        String[] args9 = { "-haf", "full(99)" };
        AccrueAnalysisOptions o9 = AccrueAnalysisOptions.getOptions(args9);
        assertTrue(o9.getHaf() instanceof FullObjSensitive);
        assertEquals("99FullObjSens+1H", o9.getHaf().toString());

        String[] args10 = { "-haf", "TypeSensitive(20,10)" };
        AccrueAnalysisOptions o10 = AccrueAnalysisOptions.getOptions(args10);
        assertTrue(o10.getHaf() instanceof TypeSensitive);
        assertEquals("20Type+10H", o10.getHaf().toString());

        String[] args11 = { "-haf", "analysis.pointer.analyses.TypeSensitive(20,11)" };
        AccrueAnalysisOptions o11 = AccrueAnalysisOptions.getOptions(args11);
        assertTrue(o11.getHaf() instanceof TypeSensitive);
        assertEquals("20Type+11H", o11.getHaf().toString());

        String[] args12 = { "-haf", "[scs(2) x type(2,1)]" };
        AccrueAnalysisOptions o12 = AccrueAnalysisOptions.getOptions(args12);
        assertTrue(o12.getHaf() instanceof CrossProduct);
        assertEquals("scs(2) x 2Type+1H", o12.getHaf().toString());

        String[] args13 = { "-haf", "[scs(2) x type(2,1) x FilterStringBuilder]" };
        AccrueAnalysisOptions o13 = AccrueAnalysisOptions.getOptions(args13);
        assertTrue(o13.getHaf() instanceof CrossProduct);
        assertEquals("scs(2) x 2Type+1H x filter(2FullObjSens+1H, java.lang.AbstractStringBuilder)", o13.getHaf()
                                                                                                        .toString());
    }
}
