package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

public record MailboxLimits(int messages, int sources) {
	public static final MailboxLimits DEFAULT = new MailboxLimits(16, 64);

	public MailboxLimits {
		if (messages <= 0 || sources <= 0) throw new IllegalArgumentException("Mailbox limits must be positive");
	}
}
