package com.nobodiiiii.createbiotech.foundation.render;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/**
 * Render-thread-only LRU for living-entity render proxies. Cached entities are
 * never added to a level and are reset to a harmless deterministic state on
 * every fetch.
 */
public final class BoundedRenderEntityCache<K, T extends LivingEntity> {
	private static final long DEFAULT_UNUSED_FRAME_LIMIT = 600;

	private final int maximumSize;
	private final BiFunction<Level, K, T> factory;
	private final LinkedHashMap<K, Entry<T>> entries = new LinkedHashMap<>(16, .75f, true);
	@Nullable
	private Level cachedLevel;
	private long frame;

	public BoundedRenderEntityCache(int maximumSize, BiFunction<Level, K, T> factory) {
		if (maximumSize <= 0)
			throw new IllegalArgumentException("maximumSize must be positive");
		this.maximumSize = maximumSize;
		this.factory = factory;
	}

	@Nullable
	public T get(@Nullable Level level, K key) {
		if (level == null)
			return null;
		if (cachedLevel != level) {
			clear();
			cachedLevel = level;
		}

		frame++;
		Entry<T> cached = entries.get(key);
		if (cached != null) {
			cached.lastUsedFrame = frame;
			reset(cached.entity);
			return cached.entity;
		}

		T entity = factory.apply(level, key);
		if (entity == null)
			return null;
		prepare(entity);
		entries.put(key, new Entry<>(entity, frame));
		trimToSize();
		return entity;
	}

	public void invalidate(K key) {
		entries.remove(key);
	}

	public void clear() {
		entries.clear();
		cachedLevel = null;
		frame = 0;
	}

	/** Removes old entries and enforces the hard LRU bound. */
	public void prune(long frameTime) {
		long oldestAllowed = frameTime - DEFAULT_UNUSED_FRAME_LIMIT;
		Iterator<Map.Entry<K, Entry<T>>> iterator = entries.entrySet().iterator();
		while (iterator.hasNext()) {
			if (iterator.next().getValue().lastUsedFrame >= oldestAllowed)
				continue;
			iterator.remove();
		}
		trimToSize();
	}

	private void trimToSize() {
		Iterator<K> iterator = entries.keySet().iterator();
		while (entries.size() > maximumSize && iterator.hasNext()) {
			iterator.next();
			iterator.remove();
		}
	}

	private static void prepare(LivingEntity entity) {
		if (entity instanceof Mob mob)
			mob.setNoAi(true);
		entity.setSilent(true);
		entity.setOnGround(true);
		entity.setYRot(0);
		entity.yRotO = 0;
		entity.setXRot(0);
		entity.xRotO = 0;
		entity.setYBodyRot(0);
		entity.yBodyRotO = 0;
		entity.yHeadRot = 0;
		entity.yHeadRotO = 0;
		reset(entity);
	}

	private static void reset(LivingEntity entity) {
		entity.hurtTime = 0;
		entity.deathTime = 0;
		entity.hurtMarked = false;
	}

	private static final class Entry<T> {
		private final T entity;
		private long lastUsedFrame;

		private Entry(T entity, long lastUsedFrame) {
			this.entity = entity;
			this.lastUsedFrame = lastUsedFrame;
		}
	}
}
