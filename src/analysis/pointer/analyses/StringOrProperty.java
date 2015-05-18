package analysis.pointer.analyses;

import util.optional.Optional;

import com.ibm.wala.classLoader.IClass;

public interface StringOrProperty {

    Optional<IClass> toIClass();

    StringOrProperty concat(StringOrProperty sop);

    StringOrProperty revconcatVisit(SOPProperty sop);

    StringOrProperty revconcatVisit(SOPString sop);

    StringOrProperty revconcatVisit(SOPProduct sop);

}
