package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

/** Explicit format/size failure; never silently substitutes an empty program or zero value. */
public final class ArchiveException extends IllegalArgumentException {
	public enum Code { INVALID_FORMAT, UNSUPPORTED_VERSION, LIMIT_EXCEEDED }
	private final Code code;
	public ArchiveException(Code code, String message) { super(message); this.code = code; }
	public Code code() { return code; }
	static ArchiveException invalid(String message) { return new ArchiveException(Code.INVALID_FORMAT, message); }
	static ArchiveException limit() { return new ArchiveException(Code.LIMIT_EXCEEDED, "Archive exceeds caller limits"); }
}
