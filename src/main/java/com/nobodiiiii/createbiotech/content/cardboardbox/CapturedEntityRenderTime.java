package com.nobodiiiii.createbiotech.content.cardboardbox;

/**
 * Scoped render-time override for captured entity icons.
 * <p>
 * Some third-party entity models ignore the partial tick supplied to their
 * renderer and read Minecraft's global timer directly. Captured entities do not
 * tick, so allowing that global value to wrap from one to zero makes their
 * renderer-owned interpolation visibly snap. This scope lets the client timer
 * mixin return the same partial tick used by the captured-entity render call.
 */
public final class CapturedEntityRenderTime {
	public static final float FIXED_PARTIAL_TICK = 1.0f;

	private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<>();

	private CapturedEntityRenderTime() {}

	static void push() {
		int[] depth = DEPTH.get();
		if (depth == null) {
			depth = new int[1];
			DEPTH.set(depth);
		}
		depth[0]++;
	}

	static void pop() {
		int[] depth = DEPTH.get();
		if (depth == null || depth[0] <= 0)
			throw new IllegalStateException("Captured entity render time scope is unbalanced");
		if (--depth[0] == 0)
			DEPTH.remove();
	}

	public static float overridePartialTick(float original) {
		int[] depth = DEPTH.get();
		return depth != null && depth[0] > 0 ? FIXED_PARTIAL_TICK : original;
	}
}
