package analysis.pointer.analyses;

import java.util.Collection;

import util.FiniteSet;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

public class ReflectiveHAF extends HeapAbstractionFactory {

    private final HeapAbstractionFactory wrappedHAF;
    private final int MAX_STRING_SET_SIZE;
    private final int MAX_CLASS_SET_SIZE;

    public static ReflectiveHAF make(int MAX_STRING_SET_SIZE, int MAX_CLASS_SET_SIZE, HeapAbstractionFactory wrappedHAF) {
        return new ReflectiveHAF(MAX_STRING_SET_SIZE, MAX_CLASS_SET_SIZE, wrappedHAF);
    }

    private ReflectiveHAF(int MAX_STRING_SET_SIZE, int MAX_CLASS_SET_SIZE, HeapAbstractionFactory wrappedHAF) {
        this.MAX_STRING_SET_SIZE = MAX_STRING_SET_SIZE;
        this.MAX_CLASS_SET_SIZE = MAX_CLASS_SET_SIZE;
        this.wrappedHAF = wrappedHAF;
    }

    @Override
    public InstanceKey record(AllocSiteNode allocationSite, Context context) {
        return this.wrappedHAF.record(allocationSite, context);
    }

    @Override
    public Context merge(CallSiteLabel callSite, InstanceKey receiver, Context callerContext) {
        if (receiver instanceof ClassInstanceKey) {
            return wrappedHAF.merge(callSite, ((ClassInstanceKey) receiver).getInnerIK(), callerContext);
        }
        else {
            return wrappedHAF.merge(callSite, receiver, callerContext);
        }
    }

    public InstanceKey recordReflective(FiniteSet<IClass> classes, AllocSiteNode allocationSite, Context context) {
        return ClassInstanceKey.make(classes, this.wrappedHAF.record(allocationSite, context));
    }

    public InstanceKey recordStringlike(AString shat, AllocSiteNode allocationSite, Context context) {
        return StringInstanceKey.make(shat, this.wrappedHAF.record(allocationSite, context));
    }

    public FiniteSet<IClass> getAClassTop() {
        return FiniteSet.makeTop(this.MAX_CLASS_SET_SIZE);
    }

    public FiniteSet<IClass> getAClassBottom() {
        return FiniteSet.makeBottom(this.MAX_CLASS_SET_SIZE);
    }

    public FiniteSet<IClass> getAClassSet(Collection<IClass> classes) {
        return FiniteSet.makeFiniteSet(this.MAX_CLASS_SET_SIZE, classes);
    }

    public AString getAStringTop() {
        return AString.makeStringTop(this.MAX_STRING_SET_SIZE);
    }

    public AString getAStringBottom() {
        return AString.makeStringBottom(this.MAX_STRING_SET_SIZE);
    }

    public AString getAStringSet(Collection<String> strings) {
        return AString.makeStringSet(this.MAX_STRING_SET_SIZE, strings);
    }

    @Override
    public Context initialContext() {
        return this.wrappedHAF.initialContext();
    }

    @Override
    public String toString() {
        return "WrapperHAF(" + MAX_STRING_SET_SIZE + ", " + MAX_CLASS_SET_SIZE + ", " + this.wrappedHAF + ")";
    }

}
