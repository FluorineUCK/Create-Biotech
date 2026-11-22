package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResidentRosterTest {
    @Test void defaultResidentBudgetsAndInterfaces() {
        var config = new BiocomputingConfig(new ModConfigSpec.Builder());
        check(config, "villager", "none", 5, 32, 32, 32, 12, 2, 2, 2, 1, false);
        check(config, "villager", "nitwit", 5, 16, 16, 64, 8, 2, 2, 2, 1, false);
        check(config, "villager", "farmer", 5, 128, 128, 128, 24, 4, 2, 4, 1, false);
        check(config, "villager", "butcher", 5, 128, 128, 128, 24, 4, 2, 2, 2, false);
        check(config, "villager", "fisherman", 5, 128, 96, 96, 24, 4, 2, 2, 1, false);
        check(config, "villager", "shepherd", 5, 128, 96, 96, 24, 4, 8, 8, 1, false);
        check(config, "villager", "armorer", 5, 320, 64, 64, 32, 4, 2, 2, 1, false);
        check(config, "villager", "mason", 5, 256, 64, 64, 32, 4, 2, 6, 1, false);
        check(config, "villager", "cartographer", 5, 96, 256, 64, 48, 4, 2, 2, 0, false);
        check(config, "villager", "leatherworker", 5, 64, 256, 64, 32, 4, 2, 2, 2, false);
        check(config, "villager", "fletcher", 5, 64, 64, 256, 24, 4, 2, 2, 0, false);
        check(config, "villager", "toolsmith", 5, 80, 64, 192, 32, 4, 2, 6, 1, false);
        check(config, "villager", "weaponsmith", 5, 64, 64, 192, 24, 4, 2, 2, 1, true);
        check(config, "villager", "librarian", 5, 256, 96, 64, 32, 8, 2, 2, 2, false);
        check(config, "villager", "cleric", 5, 256, 64, 64, 48, 4, 2, 2, 0, false);
        check(config, "wandering_trader", "none", 1, 32, 32, 32, 12, 2, 2, 2, 1, false);
        check(config, "pillager", "none", 1, 64, 48, 96, 16, 3, 2, 2, 1, false);
        check(config, "vindicator", "none", 1, 64, 32, 64, 16, 2, 2, 4, 1, false);
        check(config, "evoker", "none", 1, 160, 96, 48, 16, 12, 2, 2, 0, false);
        check(config, "illusioner", "none", 1, 80, 64, 64, 24, 3, 2, 4, 1, false);
        check(config, "witch", "none", 1, 96, 64, 48, 24, 3, 2, 2, 1, false);
        check(config, "witch", "none", 5, 96, 64, 48, 24, 3, 2, 2, 1, false);
        check(config, "zombie_villager", "none", 1, 24, 16, 16, 8, 1, 1, 1, 1, false);
    }
    private static void check(BiocomputingConfig config,String species,String job,int level,int code,int ram,int cpu,int registers,int tasks,int x,int p,int s,boolean duplex) {
        var v=config.select(new ResidentHardwareIdentity(ResourceLocation.withDefaultNamespace(species),ResourceLocation.withDefaultNamespace(job),level),false);
        if(s>0)assertEquals(species.equals("pillager")?4:1,v.limits().serial().ports().get("s0").transmitFrames());
        if(species.equals("pillager"))assertEquals(4096,v.limits().serial().ports().get("s0").transmitBytes());
        String label=species+"/"+job; assertTrue(v.ready(),label); var h=v.limits();
        assertAll(label,()->assertEquals(code,h.hardware().programUnits()),()->assertEquals(ram*1024L,h.hardware().memoryUnits()),
            ()->assertEquals(cpu,h.hardware().workPerSlice()),()->assertEquals(registers,h.hardware().registerSlots()),()->assertEquals(tasks,h.taskSlots()),()->assertEquals(x,h.pins().xbus().size()),
            ()->assertEquals(p,h.pins().simpleIo().size()),()->assertEquals(s,h.serial().ports().keySet().stream().filter(n->n.matches("s[0-9]+")).count()),
            ()->assertEquals(duplex,s>0 && h.serial().ports().get("s0").duplex().isPresent()),()->assertEquals(species.equals("witch") || job.equals("cleric"),h.isa1a()),
            ()->assertEquals(species.equals("witch") || species.equals("villager") && level==5,h.isa1b()));
    }
}
