package test.flowsenspointer;

import java.io.IOException;

import org.json.JSONException;

import com.ibm.wala.ipa.cha.ClassHierarchyException;

public class RunTestNewMT extends TestFlowSensitivePointer {
    @Override
    public void test() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/New", true);
    }
}
