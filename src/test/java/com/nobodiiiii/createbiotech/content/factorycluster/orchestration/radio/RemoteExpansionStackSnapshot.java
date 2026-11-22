package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.component.WritableBookContent;

/**
 * Immutable server-thread capture of the registry-aware exact stacks used by one remote
 * expansion. Worker code consumes only these canonical bytes and never a registry provider.
 */
public record RemoteExpansionStackSnapshot(List<Entry> entries) {
	public RemoteExpansionStackSnapshot {
		entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
		if (entries.isEmpty() || entries.size() > WritableBookContent.PAGE_EDIT_LENGTH)
			throw new IllegalArgumentException("invalid exact-stack snapshot size");
		Set<StackKey> stacks = new HashSet<>();
		Entry prior = null;
		int totalCanonicalBytes = 0;
		for (Entry entry : entries) {
			entry = Objects.requireNonNull(entry, "exact-stack snapshot entry");
			if (!stacks.add(entry.stack()))
				throw new IllegalArgumentException("duplicate exact stack in remote snapshot");
			try {
				totalCanonicalBytes = Math.addExact(totalCanonicalBytes,
					entry.canonicalBytes.length);
			} catch (ArithmeticException overflow) {
				throw new IllegalArgumentException("exact-stack snapshot byte size overflow", overflow);
			}
			if (totalCanonicalBytes > RemoteExpansionRequest.MAX_SERIALIZED_BYTES)
				throw new IllegalArgumentException("exact-stack snapshot exceeds protocol bound");
			if (prior != null && compare(prior.canonicalBytes, entry.canonicalBytes) >= 0)
				throw new IllegalArgumentException("exact-stack snapshot is not canonical");
			prior = entry;
		}
	}

	/** Capture must be invoked on the Minecraft server thread with its live registry provider. */
	public static RemoteExpansionStackSnapshot capture(HolderLookup.Provider registries,
		Collection<StackKey> stacks) {
		Objects.requireNonNull(registries, "registries");
		Objects.requireNonNull(stacks, "stacks");
		Set<StackKey> unique = new HashSet<>();
		ArrayList<Entry> captured = new ArrayList<>();
		int totalCanonicalBytes = 0;
		for (StackKey stack : stacks) {
			stack = Objects.requireNonNull(stack, "exact stack");
			if (!unique.add(stack)) continue;
			byte[] bytes = RemoteExpansionDigest.canonicalExactStack(
				stack.stack().saveOptional(registries));
			try {
				totalCanonicalBytes = Math.addExact(totalCanonicalBytes, bytes.length);
			} catch (ArithmeticException overflow) {
				throw new IllegalArgumentException("exact-stack capture byte size overflow", overflow);
			}
			if (totalCanonicalBytes > RemoteExpansionRequest.MAX_SERIALIZED_BYTES)
				throw new IllegalArgumentException("exact-stack capture exceeds protocol bound");
			captured.add(new Entry(stack, bytes, RemoteExpansionDigest.sha256(bytes)));
		}
		captured.sort((left, right) -> compare(left.canonicalBytes, right.canonicalBytes));
		return new RemoteExpansionStackSnapshot(captured);
	}

	Entry exact(StackKey stack) {
		for (Entry entry : entries)
			if (entry.stack().equals(stack)) return entry;
		throw new IllegalArgumentException("remote expansion stack was not captured");
	}

	void requireExactly(Collection<StackKey> stacks) {
		Set<StackKey> expected = Set.copyOf(Objects.requireNonNull(stacks, "stacks"));
		Set<StackKey> actual = new HashSet<>();
		entries.forEach(entry -> actual.add(entry.stack()));
		if (!actual.equals(expected))
			throw new IllegalArgumentException("remote expansion exact-stack snapshot mismatch");
	}

	/** Revalidates durable bytes on the server thread while the registry provider is available. */
	public void verify(HolderLookup.Provider registries) {
		Objects.requireNonNull(registries, "registries");
		for (Entry entry : entries) {
			byte[] expected = RemoteExpansionDigest.canonicalExactStack(
				entry.stack().stack().saveOptional(registries));
			if (!Arrays.equals(expected, entry.canonicalBytes))
				throw new IllegalArgumentException("exact-stack snapshot bytes do not match stack");
		}
	}

	private static int compare(byte[] left, byte[] right) {
		return Arrays.compareUnsigned(left, right);
	}

	public record Entry(StackKey stack, byte[] canonicalBytes, String fingerprint) {
		public Entry {
			stack = Objects.requireNonNull(stack, "stack");
			canonicalBytes = Objects.requireNonNull(canonicalBytes, "canonicalBytes").clone();
			fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
			if (canonicalBytes.length == 0
				|| canonicalBytes.length > RemoteExpansionRequest.MAX_SERIALIZED_BYTES)
				throw new IllegalArgumentException("invalid canonical exact-stack byte size");
			if (!fingerprint.equals(RemoteExpansionDigest.sha256(canonicalBytes)))
				throw new IllegalArgumentException("exact-stack fingerprint mismatch");
		}

		@Override
		public byte[] canonicalBytes() {
			return canonicalBytes.clone();
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Entry entry && stack.equals(entry.stack)
				&& Arrays.equals(canonicalBytes, entry.canonicalBytes)
				&& fingerprint.equals(entry.fingerprint);
		}

		@Override
		public int hashCode() {
			return 31 * (31 * stack.hashCode() + Arrays.hashCode(canonicalBytes))
				+ fingerprint.hashCode();
		}
	}
}
