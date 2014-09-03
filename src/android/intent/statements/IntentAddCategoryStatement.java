package android.intent.statements;

import java.util.Map;

import analysis.string.AbstractString;
import android.intent.IntentRegistrar;
import android.intent.model.AbstractIntent;

import com.ibm.wala.classLoader.IMethod;

public class IntentAddCategoryStatement extends IntentStatement {

    private final int intentValueNumber;
    private final String preciseCategory;
    private final int categoryValueNumber;

    private IntentAddCategoryStatement(int intentValueNumber, int categoryValueNumber, String preciseCategory, IMethod m) {
        super(m);
        this.preciseCategory = preciseCategory;
        this.intentValueNumber = intentValueNumber;
        this.categoryValueNumber = categoryValueNumber;
    }

    public IntentAddCategoryStatement(int intentValueNumber, String preciseCategory, IMethod m) {
        this(intentValueNumber, -1, preciseCategory, m);
    }

    public IntentAddCategoryStatement(int intentValueNumber, int categoryValueNumber, IMethod m) {
        this(intentValueNumber, categoryValueNumber, null, m);
    }

    @Override
    public boolean process(IntentRegistrar registrar, Map<Integer, AbstractString> stringResults) {
        AbstractIntent prev = registrar.getIntent(intentValueNumber);
        AbstractString category;
        if (preciseCategory == null) {
            assert categoryValueNumber >= 0 : "Invalid category value number: " + categoryValueNumber;
            category = stringResults.get(categoryValueNumber);
        }
        else {
            category = AbstractString.create(preciseCategory);
        }
        return registrar.setIntent(intentValueNumber, prev.addCategory(category));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        IntentAddCategoryStatement other = (IntentAddCategoryStatement) obj;
        if (categoryValueNumber != other.categoryValueNumber) {
            return false;
        }
        if (intentValueNumber != other.intentValueNumber) {
            return false;
        }
        if (preciseCategory == null) {
            if (other.preciseCategory != null) {
                return false;
            }
        }
        else if (!preciseCategory.equals(other.preciseCategory)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + categoryValueNumber;
        result = prime * result + intentValueNumber;
        result = prime * result + ((preciseCategory == null) ? 0 : preciseCategory.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "IntentAddCategoryStatement [intentValueNumber=" + intentValueNumber + ", preciseCategory="
                + preciseCategory + ", categoryValueNumber=" + categoryValueNumber + "]";
    }

}
