package com.nobodiiiii.createbiotech.content.cardboardbox;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBItems;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Render-thread-only cache and cooperative scheduler for captured entity icons.
 * Entity construction and renderer traversal deliberately stay on the render
 * thread; "asynchronous" here means delayed, deduplicated work with a per-frame
 * budget rather than unsafe background rendering.
 */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID, value = Dist.CLIENT)
public final class CapturedEntityRenderManager {
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final int ENTITY_CACHE_CAPACITY = 256;
	private static final int GEOMETRY_CACHE_CAPACITY = 1_024;
	private static final int FAILURE_CACHE_CAPACITY = 256;
	private static final int PENDING_CAPACITY = 2_048;
	private static final long ENTITY_IDLE_FRAMES = 60L * 60L;
	private static final long GEOMETRY_IDLE_FRAMES = 10L * 60L * 60L;
	private static final long FAILURE_RETRY_FRAMES = 30L * 60L;
	private static final long VISIBLE_REQUEST_IDLE_FRAMES = 120L;
	private static final long BACKGROUND_REQUEST_IDLE_FRAMES = 10L * 60L * 60L;
	private static final long BACKGROUND_INTERVAL_FRAMES = 4L;
	private static final long TARGET_FRAME_NANOS = 18_500_000L;
	private static final long MIN_PREPARATION_BUDGET_NANOS = 250_000L;
	private static final long GAME_PREPARATION_BUDGET_NANOS = 750_000L;
	private static final long SCREEN_PREPARATION_BUDGET_NANOS = 1_500_000L;
	private static final long CATALOG_BUDGET_NANOS = 200_000L;
	private static final int MAX_CATALOG_ITEMS_PER_FRAME = 256;

	private static final BoundedLruMap<RenderKey, CacheEntry<LivingEntity>> ENTITY_CACHE =
		new BoundedLruMap<>(ENTITY_CACHE_CAPACITY);
	private static final BoundedLruMap<RenderKey, CacheEntry<CapturedEntityBoxIconRenderer.GeometryProfile>>
		GEOMETRY_CACHE = new BoundedLruMap<>(GEOMETRY_CACHE_CAPACITY);
	private static final BoundedLruMap<RenderKey, Long> FAILURE_CACHE =
		new BoundedLruMap<>(FAILURE_CACHE_CAPACITY);
	private static final BoundedLruMap<RenderKey, PendingRequest> PENDING =
		new BoundedLruMap<>(PENDING_CAPACITY);

	@Nullable
	private static Level activeLevel;
	private static long frame;
	private static long lastFrameNanos;
	private static double averageFrameNanos = 16_666_667.0d;
	private static boolean prewarmQueued;
	@Nullable
	private static Iterator<Item> prewarmItems;
	@Nullable
	private static Set<EntityType<?>> prewarmEntityTypes;

	private CapturedEntityRenderManager() {
	}

	@Nullable
	static PreparedIcon getOrSchedule(CapturedEntityBoxHelper.CapturedEntityRenderData renderData,
		RequestPriority priority) {
		if (!CBConfigs.CLIENT.renderCapturedEntitiesOnBoxes.get())
			return null;

		Minecraft minecraft = Minecraft.getInstance();
		Level level = minecraft.level;
		if (level == null)
			return null;

		ensureLevel(level);
		RenderKey key = RenderKey.of(renderData);
		Long retryAt = FAILURE_CACHE.get(key);
		if (retryAt != null) {
			if (frame < retryAt)
				return null;
			FAILURE_CACHE.remove(key);
		}

		LivingEntity entity = getCached(ENTITY_CACHE, key);
		CapturedEntityBoxIconRenderer.GeometryProfile geometry = getCached(GEOMETRY_CACHE, key);
		if (entity != null && geometry != null)
			return new PreparedIcon(entity, geometry);

		enqueue(key, renderData, priority);
		return null;
	}

	public static void clearForResourceReload() {
		clearAll();
	}

	@SubscribeEvent
	public static void onRenderFrame(RenderFrameEvent.Post event) {
		if (!CBConfigs.CLIENT.renderCapturedEntitiesOnBoxes.get()) {
			if (activeLevel != null || prewarmQueued || !ENTITY_CACHE.isEmpty() || !GEOMETRY_CACHE.isEmpty()
				|| !FAILURE_CACHE.isEmpty() || !PENDING.isEmpty())
				clearAll();
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		long now = System.nanoTime();
		updateFrameTiming(now);
		frame++;

		Level level = minecraft.level;
		if (level == null || minecraft.player == null) {
			if (activeLevel != null)
				clearAll();
			return;
		}

		ensureLevel(level);
		if (!prewarmQueued)
			beginCreativePrewarm();
		advanceCreativePrewarmCatalog();
		pruneExpiredEntries();
		processPending(minecraft, level, System.nanoTime());
	}

	@SubscribeEvent
	public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
		clearAll();
	}

	private static void updateFrameTiming(long now) {
		if (lastFrameNanos != 0L) {
			long duration = Math.min(now - lastFrameNanos, 250_000_000L);
			averageFrameNanos += (duration - averageFrameNanos) * 0.1d;
		}
		lastFrameNanos = now;
	}

	private static void ensureLevel(Level level) {
		if (activeLevel == level)
			return;
		clearAll();
		activeLevel = level;
	}

	private static void beginCreativePrewarm() {
		prewarmQueued = true;
		prewarmItems = BuiltInRegistries.ITEM.iterator();
		prewarmEntityTypes = Collections.newSetFromMap(new IdentityHashMap<>());
	}

	private static void advanceCreativePrewarmCatalog() {
		if (prewarmItems == null || prewarmEntityTypes == null)
			return;

		long start = System.nanoTime();
		int inspected = 0;
		while (prewarmItems.hasNext() && inspected++ < MAX_CATALOG_ITEMS_PER_FRAME
			&& System.nanoTime() - start < CATALOG_BUDGET_NANOS) {
			Item item = prewarmItems.next();
			if (!(item instanceof SpawnEggItem spawnEgg))
				continue;

			EntityType<?> entityType = spawnEgg.getType(item.getDefaultInstance());
			if (entityType == null || !prewarmEntityTypes.add(entityType))
				continue;

			ItemStack prototype = CapturedEntityBoxHelper.createFilledBox(CBItems.LARGE_CARDBOARD_BOX.get(), entityType);
			CapturedEntityBoxHelper.CapturedEntityRenderData renderData =
				CapturedEntityBoxHelper.getCapturedEntityRenderData(prototype);
			if (renderData != null)
				enqueue(RenderKey.of(renderData), renderData, RequestPriority.BACKGROUND);
		}

		if (!prewarmItems.hasNext()) {
			prewarmItems = null;
			prewarmEntityTypes = null;
		}
	}

	private static void enqueue(RenderKey key, CapturedEntityBoxHelper.CapturedEntityRenderData renderData,
		RequestPriority priority) {
		PendingRequest current = PENDING.get(key);
		if (current == null) {
			PENDING.put(key, new PendingRequest(renderData, priority, frame));
			return;
		}

		current.lastRequestedFrame = frame;
		if (priority.weight > current.priority.weight)
			current.priority = priority;
	}

	private static void processPending(Minecraft minecraft, Level level, long startNanos) {
		if (PENDING.isEmpty())
			return;

		PendingSelection selection = selectPending();
		if (selection == null)
			return;
		if (selection.request.priority == RequestPriority.BACKGROUND) {
			if (frame % BACKGROUND_INTERVAL_FRAMES != 0L || averageFrameNanos > TARGET_FRAME_NANOS)
				return;
		}

		long configuredBudget = minecraft.screen == null
			? GAME_PREPARATION_BUDGET_NANOS : SCREEN_PREPARATION_BUDGET_NANOS;
		long measuredHeadroom = Math.max(MIN_PREPARATION_BUDGET_NANOS,
			(long) ((TARGET_FRAME_NANOS - averageFrameNanos) * 0.25d));
		long budget = Math.min(configuredBudget, measuredHeadroom);
		int maxTasks = minecraft.screen == null ? 1 : 2;
		int completed = 0;

		while (selection != null && completed < maxTasks) {
			PENDING.remove(selection.key);
			prepare(selection.key, selection.request.renderData, level);
			completed++;
			if (System.nanoTime() - startNanos >= budget)
				break;

			selection = selectPending();
			if (selection != null && selection.request.priority == RequestPriority.BACKGROUND)
				break;
		}
	}

	@Nullable
	private static PendingSelection selectPending() {
		RenderKey selectedKey = null;
		PendingRequest selected = null;
		Iterator<Map.Entry<RenderKey, PendingRequest>> iterator = PENDING.entrySet()
			.iterator();
		while (iterator.hasNext()) {
			Map.Entry<RenderKey, PendingRequest> entry = iterator.next();
			PendingRequest request = entry.getValue();
			long maxIdle = request.priority == RequestPriority.BACKGROUND
				? BACKGROUND_REQUEST_IDLE_FRAMES : VISIBLE_REQUEST_IDLE_FRAMES;
			if (frame - request.lastRequestedFrame > maxIdle) {
				iterator.remove();
				continue;
			}

			if (selected == null || request.priority.weight > selected.priority.weight
				|| request.priority == selected.priority
					&& request.lastRequestedFrame > selected.lastRequestedFrame) {
				selectedKey = entry.getKey();
				selected = request;
			}
		}
		return selected == null ? null : new PendingSelection(selectedKey, selected);
	}

	private static void prepare(RenderKey key, CapturedEntityBoxHelper.CapturedEntityRenderData renderData,
		Level level) {
		try {
			LivingEntity entity = getCached(ENTITY_CACHE, key);
			if (entity == null) {
				Entity loaded = CapturedEntityBoxHelper.createCapturedEntity(renderData, level);
				if (!(loaded instanceof LivingEntity living))
					throw new IllegalStateException("Captured entity is not a living entity: " + renderData.entityId());
				stabilize(living);
				entity = living;
			}

			CapturedEntityBoxIconRenderer.GeometryProfile geometry = getCached(GEOMETRY_CACHE, key);
			if (geometry == null)
				geometry = CapturedEntityBoxIconRenderer.prepareGeometry(entity);

			ENTITY_CACHE.put(key, new CacheEntry<>(entity, frame));
			GEOMETRY_CACHE.put(key, new CacheEntry<>(geometry, frame));
		} catch (RuntimeException exception) {
			FAILURE_CACHE.put(key, frame + FAILURE_RETRY_FRAMES);
			LOGGER.warn("Unable to prepare captured entity icon for {}", renderData.entityId(), exception);
		}
	}

	private static void stabilize(LivingEntity entity) {
		if (entity instanceof Mob mob)
			mob.setNoAi(true);
		entity.setSilent(true);
		entity.setOnGround(true);
		entity.setDeltaMovement(Vec3.ZERO);
		entity.tickCount = 0;
		entity.hurtTime = 0;
		entity.deathTime = 0;
		entity.hurtMarked = false;
		entity.setYRot(0.0f);
		entity.yRotO = 0.0f;
		entity.setXRot(0.0f);
		entity.xRotO = 0.0f;
		entity.setYBodyRot(0.0f);
		entity.yBodyRotO = 0.0f;
		entity.yHeadRot = 0.0f;
		entity.yHeadRotO = 0.0f;
	}

	@Nullable
	private static <T> T getCached(BoundedLruMap<RenderKey, CacheEntry<T>> cache, RenderKey key) {
		CacheEntry<T> entry = cache.get(key);
		if (entry == null)
			return null;
		entry.lastAccessFrame = frame;
		return entry.value;
	}

	private static void pruneExpiredEntries() {
		if (frame % 60L != 0L)
			return;
		pruneCache(ENTITY_CACHE, ENTITY_IDLE_FRAMES);
		pruneCache(GEOMETRY_CACHE, GEOMETRY_IDLE_FRAMES);
		FAILURE_CACHE.entrySet()
			.removeIf(entry -> frame >= entry.getValue());
	}

	private static <T> void pruneCache(BoundedLruMap<RenderKey, CacheEntry<T>> cache, long maxIdleFrames) {
		cache.entrySet()
			.removeIf(entry -> frame - entry.getValue().lastAccessFrame > maxIdleFrames);
	}

	private static void clearAll() {
		ENTITY_CACHE.clear();
		GEOMETRY_CACHE.clear();
		FAILURE_CACHE.clear();
		PENDING.clear();
		activeLevel = null;
		prewarmQueued = false;
		prewarmItems = null;
		prewarmEntityTypes = null;
	}

	static record PreparedIcon(LivingEntity entity,
		CapturedEntityBoxIconRenderer.GeometryProfile geometry) {
	}

	enum RequestPriority {
		BACKGROUND(0),
		WORLD(1),
		GUI(2),
		HELD(3);

		private final int weight;

		RequestPriority(int weight) {
			this.weight = weight;
		}

		static RequestPriority forDisplayContext(ItemDisplayContext context) {
			return switch (context) {
			case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND, THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> HELD;
			case GUI -> GUI;
			default -> WORLD;
			};
		}
	}

	private interface RenderKey {
		static RenderKey of(CapturedEntityBoxHelper.CapturedEntityRenderData renderData) {
			return renderData.prototype()
				? new PrototypeKey(renderData.entityId()) : new ComponentIdentityKey(renderData.component());
		}
	}

	private record PrototypeKey(String entityId) implements RenderKey {
	}

	private static final class ComponentIdentityKey implements RenderKey {
		private final CustomData component;
		private final int hash;

		private ComponentIdentityKey(CustomData component) {
			this.component = component;
			hash = System.identityHashCode(component);
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof ComponentIdentityKey other && component == other.component;
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private static final class PendingRequest {
		private final CapturedEntityBoxHelper.CapturedEntityRenderData renderData;
		private RequestPriority priority;
		private long lastRequestedFrame;

		private PendingRequest(CapturedEntityBoxHelper.CapturedEntityRenderData renderData, RequestPriority priority,
			long frame) {
			this.renderData = renderData;
			this.priority = priority;
			lastRequestedFrame = frame;
		}
	}

	private record PendingSelection(RenderKey key, PendingRequest request) {
	}

	private static final class CacheEntry<T> {
		private final T value;
		private long lastAccessFrame;

		private CacheEntry(T value, long lastAccessFrame) {
			this.value = value;
			this.lastAccessFrame = lastAccessFrame;
		}
	}

	private static final class BoundedLruMap<K, V> extends LinkedHashMap<K, V> {
		private final int capacity;

		private BoundedLruMap(int capacity) {
			super(capacity, 0.75f, true);
			this.capacity = capacity;
		}

		@Override
		protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
			return size() > capacity;
		}
	}
}
