package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import net.minecraft.nbt.CompoundTag;

class FactoryPanelAgentAccessTest {
    @Test
    void malformedFrequencyNbtClearsTheRealAgentCompoundWithoutThrowing() {
        FactoryPanelAgentAccess.AgentCompound compound = new FactoryPanelAgentAccess.AgentCompound();
        compound.set(configuration());
        CompoundTag saved = new CompoundTag();
        compound.write(saved);
        saved.getCompound(FactoryPanelAgentAccess.ROOT_KEY).putString("frequency", "x".repeat(26));

        assertDoesNotThrow(() -> compound.read(saved));
        assertTrue(compound.get().isEmpty());
    }

    private static FactoryPanelAgentAccess.Configuration configuration() {
        return new FactoryPanelAgentAccess.Configuration(new UUID(0, 1), new UUID(0, 2),
            new UUID(0, 3), 4, 5, AgentEndpointRole.INPUT, "input", 0,
            CommunicationFrequency.of("literal"), AgentCompletionMode.TARGET, Optional.empty(),
            AgentEndpointState.OutputMode.WAREHOUSE_ALL, 6,
            new CreateRouteKey(new UUID(0, 7), "warehouse"));
    }
}
