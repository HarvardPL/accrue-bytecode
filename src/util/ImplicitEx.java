package util;

import util.print.PrettyPrinter;

import com.ibm.wala.types.TypeReference;

public enum ImplicitEx {
    NULL_POINTER_EXCEPTION, ARRAY_INDEX_OUT_OF_BOUNDS_EXCEPTION, ARRAY_STORE_EXCEPTION, ARITHMETIC_EXCEPTION, CLASS_CAST_EXCEPTION, NEGATIVE_ARRAY_SIZE_EXCEPTION, CLASS_NOT_FOUND_EXCEPTION;

    public static ImplicitEx fromType(TypeReference type) {
        if (type == TypeReference.JavaLangNullPointerException) {
            return NULL_POINTER_EXCEPTION;
        }
        if (type == TypeReference.JavaLangArrayIndexOutOfBoundsException) {
            return ARRAY_INDEX_OUT_OF_BOUNDS_EXCEPTION;
        }
        if (type == TypeReference.JavaLangArrayStoreException) {
            return ARRAY_STORE_EXCEPTION;
        }
        if (type == TypeReference.JavaLangArithmeticException) {
            return ARITHMETIC_EXCEPTION;
        }
        if (type == TypeReference.JavaLangClassCastException) {
            return CLASS_CAST_EXCEPTION;
        }
        if (type == TypeReference.JavaLangNegativeArraySizeException) {
            return NEGATIVE_ARRAY_SIZE_EXCEPTION;
        }
        if (type == TypeReference.JavaLangClassNotFoundException) {
            return CLASS_NOT_FOUND_EXCEPTION;
        }
        throw new RuntimeException("Unknown implicit exception type: " + PrettyPrinter.typeString(type));
    }
}
