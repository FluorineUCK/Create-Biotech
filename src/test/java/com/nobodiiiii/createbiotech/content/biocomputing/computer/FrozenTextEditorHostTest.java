package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec;
import java.util.List;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FrozenTextEditorHostTest {
    @BeforeAll static void bootstrap() throws Exception { ComputerBehaviourTest.bootstrap(); }

    @Test void readingTheRecoveredEditorDocumentDoesNotRewriteTheInstalledComputer() throws Exception {
        var original = NbtIo.readCompressed(getClass().getResourceAsStream("/old-text-editor-player.nbt"),
            NbtAccounter.create(16 * 1024 * 1024));
        var block = new ComputerBehaviourTest.Harness();
        block.selection = new BiocomputingConfig(new ModConfigSpec.Builder()).select(new ResidentHardwareIdentity(
            ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:cleric"), 5), false);
        var host = block.getBehaviour(ComputerBehaviour.TYPE);
        host.read(original, RegistryAccess.EMPTY, false);
        var before = FlowBlockNbtCodec.decode(original.getCompound(ComputerBehaviour.ROOT), block.selection.limits().archive());
        assertEquals(FlowRuntime.Status.COMPLETE, before.state().status());
        assertEquals(5934, before.state().work());
        long revision = host.programRevision();
        var source = host.document().orElseThrow(() -> new AssertionError("Editor unavailable: " + host.diagnostic()));
        assertEquals(Set.of("block_1", "block_2"), source.flow().orElseThrow().inline().keySet());
        assertTrue(host.diagnostic().isEmpty(), "The authorized editor must not fall back to metadata-free runtime source");
        assertEquals(before, host.flowSnapshot().orElseThrow());
        assertEquals(revision, host.programRevision());
        var rewritten = new CompoundTag();
        host.write(rewritten, RegistryAccess.EMPTY, false);
        for (String key : List.of(ComputerBehaviour.ROOT, ComputerBehaviour.EDITOR_ROOT, ComputerBehaviour.INSTALLATION_ROOT)) {
            assertEquals(original.get(key), rewritten.get(key), key);
        }
    }
}
