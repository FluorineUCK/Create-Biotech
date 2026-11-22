package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;

/** Canonical lossless quoting boundary for literal strings shown by the field browser. */
public final class BrowserLiteralDisplay {
    private BrowserLiteralDisplay() {}

    public static String quote(String value) {
        var result = new StringBuilder(value.length() + 2).append('"');
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case 0x00a7 -> result.append("\\u00a7");
                case 0x2028 -> result.append("\\u2028");
                case 0x2029 -> result.append("\\u2029");
                default -> {
                    if (codePoint <= 0x1f || codePoint >= 0x7f && codePoint <= 0x9f)
                        result.append(String.format("\\u%04x", codePoint));
                    else result.appendCodePoint(codePoint);
                }
            }
        }
        return result.append('"').toString();
    }

    static String leaf(DataValue value) {
        if (value instanceof DataValue.TextValue text) return quote(text.value());
        if (value instanceof DataValue.ErrorValue error)
            return "error{code:" + quote(error.code()) + ",message:" + quote(error.message()) + "}";
        return ComputerValueDisplay.lines(value, Integer.MAX_VALUE).getFirst();
    }
}
