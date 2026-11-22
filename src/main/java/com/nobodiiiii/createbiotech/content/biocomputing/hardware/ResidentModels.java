package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.resources.ResourceLocation;

/** Fresh-world balance. Trading residents use the row at master level; unranked models have fixed hardware. */
final class ResidentModels {
    record Row(String identity, String profile, boolean entity, boolean ranked, int code, int ramKi, int cpu,
               int registers, int tasks, int x, int p, int serial, boolean duplex) {
        String binding() { return "minecraft:" + identity + "=" + profile; }
        List<Long> levels(long peak) {
            return (ranked ? List.of(2L,3L,4L,6L,8L) : List.of(8L,8L,8L,8L,8L))
                .stream().map(n -> Math.max(1L, peak*n/8)).toList();
        }
        List<Long> taskLevels() {
            if (!ranked) return java.util.Collections.nCopies(5,(long)tasks);
            return tasks == 8 ? List.of(4L,5L,6L,7L,8L) : List.of(2L,2L,3L,3L,4L);
        }
    }
    static List<String> pins(String prefix,int n) { return IntStream.range(0,n).mapToObj(i->prefix+i).toList(); }
    static final List<Row> ROWS = List.of(
        new Row("none", "starter", false, false, 32, 32, 32, 12, 2, 2, 2, 1, false),
        new Row("nitwit", "nitwit", false, false, 16, 16, 64, 8, 2, 2, 2, 1, false),
        new Row("farmer", "balanced", false, true, 128, 128, 128, 24, 4, 2, 4, 1, false),
        new Row("butcher", "butcher", false, true, 128, 128, 128, 24, 4, 2, 2, 2, false),
        new Row("fisherman", "fisherman", false, true, 128, 96, 96, 24, 4, 2, 2, 1, false),
        new Row("shepherd", "shepherd", false, true, 128, 96, 96, 24, 4, 8, 8, 1, false),
        new Row("armorer", "program", false, true, 320, 64, 64, 32, 4, 2, 2, 1, false),
        new Row("mason", "mason", false, true, 256, 64, 64, 32, 4, 2, 6, 1, false),
        new Row("cartographer", "memory", false, true, 96, 256, 64, 48, 4, 2, 2, 0, false),
        new Row("leatherworker", "leatherworker", false, true, 64, 256, 64, 32, 4, 2, 2, 2, false),
        new Row("fletcher", "speed", false, true, 64, 64, 256, 24, 4, 2, 2, 0, false),
        new Row("toolsmith", "toolsmith", false, true, 80, 64, 192, 32, 4, 2, 6, 1, false),
        new Row("weaponsmith", "weaponsmith", false, true, 64, 64, 192, 24, 4, 2, 2, 1, true),
        new Row("librarian", "control", false, true, 256, 96, 64, 32, 8, 2, 2, 2, false),
        new Row("cleric", "parser", false, true, 256, 64, 64, 48, 4, 2, 2, 0, false),
        new Row("wandering_trader", "trader", true, false, 32, 32, 32, 12, 2, 2, 2, 1, false),
        new Row("pillager", "pillager", true, false, 64, 48, 96, 16, 3, 2, 2, 1, false),
        new Row("vindicator", "vindicator", true, false, 64, 32, 64, 16, 2, 2, 4, 1, false),
        new Row("evoker", "evoker", true, false, 160, 96, 48, 16, 12, 2, 2, 0, false),
        new Row("illusioner", "illusioner", true, false, 80, 64, 64, 24, 3, 2, 4, 1, false),
        new Row("witch", "witch", true, false, 96, 64, 48, 24, 3, 2, 2, 1, false),
        new Row("zombie_villager", "zombie", true, false, 24, 16, 16, 8, 1, 1, 1, 1, false));
    static final java.util.Set<ResourceLocation> ENTITY_IDS = ROWS.stream().filter(Row::entity)
        .map(r->ResourceLocation.withDefaultNamespace(r.identity())).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private ResidentModels() {}
}
