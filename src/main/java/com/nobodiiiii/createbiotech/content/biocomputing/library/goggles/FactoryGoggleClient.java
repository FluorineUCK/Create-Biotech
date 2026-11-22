package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryGoggleDetails;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.simibubi.create.content.equipment.goggles.GogglesItem;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client main-thread adapter. Only its tick entry point may dispatch requests. */
public final class FactoryGoggleClient {
	private static final FactoryGoggleClient INSTANCE = new FactoryGoggleClient(
		FactoryGoggleClient::observeMinecraft, CBPackets::sendToServer,
		LibraryGoggleDetails::interval);

	private final FactoryGoggleClientCache cache = new FactoryGoggleClientCache();
	private final Supplier<Observation> observations;
	private final Consumer<AdvancedGoggleRequestPacket> send;
	private final IntSupplier interval;
	private Object world;
	private long tick;

	FactoryGoggleClient(Supplier<Observation> observations,
		Consumer<AdvancedGoggleRequestPacket> send, IntSupplier interval) {
		this.observations = observations;
		this.send = send;
		this.interval = interval;
	}

	/** A captured client identity boundary, never a server runtime or worker snapshot. */
	record Observation(Object world, FactoryGoggleTarget target) {}

	private static Observation observeMinecraft() {
		var mc = Minecraft.getInstance();
		return new Observation(mc.level, target(mc));
	}

	private static FactoryGoggleTarget target(Minecraft mc) {
		if (mc.level == null || mc.player == null || mc.screen != null
			|| !mc.player.isShiftKeyDown() || !GogglesItem.isWearingGoggles(mc.player)) return null;
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
			|| !mc.level.hasChunkAt(hit.getBlockPos())) return null;
		var pos = FactoryGoggleProxy.resolve(mc.level, hit.getBlockPos());
		var be = SubLevelCompat.getLoadedBlockEntity(mc.level, pos);
		if (be == null || be.isRemoved() || !(be instanceof FactoryGoggleInformation info)) return null;
		return info.advancedGoggleMetadata().clientTarget()
			.filter(target -> target.address().matches(mc.level, pos)).orElse(null);
	}

	public static void tick(ClientTickEvent.Post event) {
		INSTANCE.clientTick();
	}

	public static void accept(FactoryAdvancedGoggleProjection response, long requestId) {
		INSTANCE.receive(response, requestId);
	}

	public static void append(FactoryGoggleInformation owner, List<Component> tooltip) {
		INSTANCE.appendTooltip(owner, tooltip);
	}

	/** Every entry point applies observed identity changes before consulting cached data or IDs. */
	private Observation synchronizeObservation() {
		var observation = observations.get();
		if (world != observation.world()) {
			cache.clear();
			world = observation.world();
			tick = 0;
		}
		cache.synchronizeTarget(observation.target());
		return observation;
	}

	void clientTick() {
		var current = synchronizeObservation().target();
		tick++;
		if (cache.observe(current, true, true, tick, interval.getAsInt()))
			send.accept(new AdvancedGoggleRequestPacket(current, cache.requestId()));
	}

	void receive(FactoryAdvancedGoggleProjection response, long requestId) {
		var current = synchronizeObservation().target();
		if (current == null) return;
		if (!current.equals(response.target())) return;
		cache.accept(response, requestId, tick);
	}

	void appendTooltip(FactoryGoggleInformation owner, List<Component> tooltip) {
		var current = synchronizeObservation().target();
		if (current == null) return;
		if (owner.advancedGoggleMetadata().clientTarget().filter(current::equals).isEmpty()) return;
		cache.value(tick).filter(projection -> projection.target().equals(current)).ifPresent(projection -> {
			tooltip.add(Component.literal("  [Advanced] " + projection.target().memberId()
				+ " / " + projection.target().generation()));
			var address = current.address();
			tooltip.add(Component.literal("  " + address.dimension().location() + "  " + address.localPos().toShortString()));
			if (address.subLevelId() != null) tooltip.add(Component.literal("  sublevel=" + address.subLevelId()));
			tooltip.add(Component.literal("  incarnation=" + projection.target().incarnation()
				+ " sampleTick=" + projection.sampleTick()));
			projection.collections().forEach(list -> list.forEach(line ->
				tooltip.add(Component.literal("  " + line))));
		});
	}
}
