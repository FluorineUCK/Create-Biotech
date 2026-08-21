package com.nobodiiiii.createbiotech.foundation.render;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/**
 * Render-thread-only LRU for living-entity render proxies. Cached entities are
 * never added to a level and are reset to a harmless deterministic state on
 * every fetch.
 * <p>
 * Every instance registers itself so that {@link RenderEntityCacheLifecycle} can drop entries when
 * the client level goes away: both the cache and the entities it holds reference a {@link Level},
 * so keeping them past a disconnect would pin the whole {@code ClientLevel} in memory.
 */
public final class BoundedRenderEntityCache<K, T extends LivingEntity> {
	private static final int DEFAULT_UNUSED_TICK_LIMIT = 600;
	private static final List<BoundedRenderEntityCache<?, ?>> INSTANCES = new ArrayList<>();

	private final int maximumSize;
	private final BiFunction<Level, K, T> factory;
	private final LinkedHashMap<K, Entry<T>> entries = new LinkedHashMap<>(16, .75f, true);
	@Nullable
	private Level cachedLevel;

	public BoundedRenderEntityCache(int maximumSize, BiFunction<Level, K, T> factory) {
		if (maximumSize <= 0)
			throw new IllegalArgumentException("maximumSize must be positive");
		this.maximumSize = maximumSize;
		this.factory = factory;
		INSTANCES.add(this);
	}

	/** Drops every cached entity in every cache, releasing the level references they hold. */
	public static void clearAll() {
		for (BoundedRenderEntityCache<?, ?> cache : INSTANCES)
			cache.clear();
	}

	/** Evicts entities that have not been drawn recently, in every cache. */
	public static void pruneAll() {
		for (BoundedRenderEntityCache<?, ?> cache : INSTANCES)
			cache.prune();
	}

	@Nullable
	public T get(@Nullable Level level, K key) {
		if (level == null)
			return null;
		if (cachedLevel != level) {
			clear();
			cachedLevel = level;
		}

		Entry<T> cached = entries.get(key);
		if (cached != null) {
			cached.lastUsedTick = AnimationTickHolder.getTicks();
			reset(cached.entity);
			return cached.entity;
		}

		T entity = factory.apply(level, key);
		if (entity == null)
			return null;
		prepare(entity);
		entries.put(key, new Entry<>(entity, AnimationTickHolder.getTicks()));
		trimToSize();
		return entity;
	}

	public void invalidate(K key) {
		Entry<T> removed = entries.remove(key);
		if (removed != null)
			RenderProxyEntities.forget(removed.entity);
	}

	public void clear() {
		for (Entry<T> entry : entries.values())
			RenderProxyEntities.forget(entry.entity);
		entries.clear();
		cachedLevel = null;
	}

	/** Removes entries unused for {@value #DEFAULT_UNUSED_TICK_LIMIT} ticks and enforces the LRU bound. */
	public void prune() {
		if (entries.isEmpty())
			return;
		int oldestAllowed = AnimationTickHolder.getTicks() - DEFAULT_UNUSED_TICK_LIMIT;
		Iterator<Map.Entry<K, Entry<T>>> iterator = entries.entrySet().iterator();
		while (iterator.hasNext()) {
			Entry<T> entry = iterator.next().getValue();
			if (entry.lastUsedTick >= oldestAllowed)
				continue;
			RenderProxyEntities.forget(entry.entity);
			iterator.remove();
		}
		trimToSize();
		if (entries.isEmpty())
			cachedLevel = null;
	}

	private void trimToSize() {
		Iterator<Map.Entry<K, Entry<T>>> iterator = entries.entrySet().iterator();
		while (entries.size() > maximumSize && iterator.hasNext()) {
			RenderProxyEntities.forget(iterator.next().getValue().entity);
			iterator.remove();
		}
	}

	private static void prepare(LivingEntity entity) {
		RenderProxyEntities.mark(entity);
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
		private int lastUsedTick;

		private Entry(T entity, int lastUsedTick) {
			this.entity = entity;
			this.lastUsedTick = lastUsedTick;
		}
	}
}
