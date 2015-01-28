package test.flowsenspointer;

import java.io.IOException;

import org.json.JSONException;

import com.ibm.wala.ipa.cha.ClassHierarchyException;

public class RunTestPreserve extends TestFlowSensitivePointer {
    @Override
    public void test() throws ClassHierarchyException, IOException, JSONException {
        runTest("test/flowsenspointer/Preserve", false);
    }
}
