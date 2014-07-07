package android;

import android.manifest.ManifestFile;

import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;

public class AndroidInit {

    private ManifestFile manifest;

    public AbstractRootMethod getFakeRoot() {
        return null;
    }

    private void processManifest(String apkFileName) {
        this.manifest = new ManifestFile(apkFileName);
    }
}
