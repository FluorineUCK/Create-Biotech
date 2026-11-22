package com.nobodiiiii.createbiotech.content.biocomputing.computer.resident;

import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.CapturedResidentHardware;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerInstallResult.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentInstallation.*;

/** Loaded-world admission and detached entity decoding; no production model or world mutation. */
public final class ComputerResidentInspector implements Operations {
    private final ServerLevel level;
    public ComputerResidentInspector(ServerLevel level) { this.level = java.util.Objects.requireNonNull(level); }
    @Override public boolean isBox(ItemStack stack) { return CapturedEntityBoxItem.isBox(stack); }
    @Override public boolean hasCapturedEntity(ItemStack stack) { return CapturedEntityBoxHelper.hasCapturedEntity(stack); }
    @Override public void clearCapturedEntity(ItemStack stack) { CapturedEntityBoxHelper.clearCapturedEntity(stack); }

    @Override public Inspection inspect(ItemStack snapshot) {
        var raw = ComputerResident.identity(snapshot);
        if (raw == null) return Inspection.failure(INVALID_IDENTITY);
        if (raw.passengers()) return Inspection.failure(PASSENGERS_UNSUPPORTED);
        for (var world : level.getServer().getAllLevels())
            if (world.getEntity(raw.uuid()) != null) return Inspection.failure(INVALID_IDENTITY);
        Entity decoded = CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(snapshot, level);
        if (decoded == null || !decoded.getUUID().equals(raw.uuid())
            || decoded.getType() != BuiltInRegistries.ENTITY_TYPE.get(raw.id()))
            return Inspection.failure(INVALID_IDENTITY);
        VillagerProfession profession = VillagerProfession.NONE;
        int experience = 1;
        if (decoded instanceof Villager villager) {
            if (villager.isBaby()) return Inspection.failure(UNSUPPORTED_ENTITY);
            profession = villager.getVillagerData().getProfession();
            experience = villager.getVillagerData().getLevel();
            if (experience < 1 || experience > 5) return Inspection.failure(UNSUPPORTED_ENTITY);
        } else if (!ResidentHardwareIdentity.fixedSpecies(raw.id()) || !(decoded instanceof LivingEntity living)
                || living.isBaby()) return Inspection.failure(UNSUPPORTED_ENTITY);
        var hardware = new ResidentHardwareIdentity(raw.id(), BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession), experience);
        // Entity codecs can default malformed fields. Never install a raw snapshot that the VM
        // subsequently rejects or interprets as a different resident; preserve rather than rewrite it.
        if (CapturedResidentHardware.readIdentity(snapshot).filter(hardware::equals).isEmpty())
            return Inspection.failure(INVALID_IDENTITY);
        return Inspection.success(new Candidate(raw.uuid(), hardware));
    }
}
