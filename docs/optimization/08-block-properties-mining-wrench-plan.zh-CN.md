# 08. 方块属性、挖掘、掉落与扳手一致性改造方案

## 1. 方案结论

本次改造采用 Create 自身的分层方式，不创建一个同时控制属性、工具标签和扳手行为的“大而全模板”。最终结构分为四个彼此独立、在方块注册处组合的层次：

1. `CBSharedProperties`：复用 Create `SharedProperties` 和原版方块，提供新的 `Block.Properties`。
2. `CBBlockTagsProvider`：集中生成挖掘工具与工具等级标签。
3. 方块继承与 `IWrenchable`：复用 Create 的默认旋转、潜行拆除和声音逻辑。
4. loot table 或方块自定义掉落：决定是否掉落自身、是否需要精准采集、是否只掉经验。

主注册表继续统一使用 `DeferredRegister`，不为本次改造整体迁移到 Registrate。Butter Cat 原有的独立 `CreateRegistrate` 注册链也已收敛到主 `registry`：方块、物品、流体、方块实体、效果和药水均由主注册表声明，压力/RPM、机械臂、客户端渲染和 Ponder 挂接由主入口统一完成；`content/buttercat` 只保留玩法、渲染与兼容行为逻辑。

目标结果：玩家面对一个看起来或功能上对应原版/Create 的方块时，挖掘工具、掉落要求、扳手旋转和潜行拆除都符合对应方块的直觉；特殊行为只保留有明确玩法理由的例外。

本方案已经确认以下项目设计决定：

- 唤魔者附魔室的属性与挖掘工具直接对齐原版附魔台，不再采用 Create 软金属设备基线。
- 全部防爆系列的硬度、爆炸抗性、正确工具要求、工具类型和工具等级对齐黑曜石；其余外观、音效、碰撞和机械行为对齐各自对应的 Create 机壳、物品保险库、链式传动箱或原版玻璃。
- 两种防爆玻璃在使用正确工具破坏时正常掉落自身，不采用原版玻璃必须精准采集才掉落的规则。
- 培养皿支持普通扳手旋转和潜行扳手拆除。
- 蜘蛛装配台及其齿轮不再覆盖羊毛音效。
- 两种 Butter Cat 动力方块的属性与挖掘工具对齐 Create 传动轴。

## 2. 基准与参考

- 当前项目：Minecraft 1.20.1、Forge 47.1.33、Create 6.0.8-291。
- Create 本地参考：`ref/1.20.1/Create`，对应 `mc1.20.1/dev` 提交 `588124239141029c5321afd38d20c2eaf872b32b`。
- 原版参考：`ref/_legacy_pre_reorg_20260720_001540/Minecraft-1.20.1`。
- CEI 仅用于打印机和附魔设备的概念对照；其本地版本不是当前运行依赖的精确版本，因此不直接复制实现。

关键参考文件：

- `ref/1.20.1/Create/src/main/java/com/simibubi/create/foundation/data/SharedProperties.java`
- `ref/1.20.1/Create/src/main/java/com/simibubi/create/foundation/data/TagGen.java`
- `ref/1.20.1/Create/src/main/java/com/simibubi/create/foundation/data/BuilderTransformers.java`
- `ref/1.20.1/Create/src/main/java/com/simibubi/create/AllBlocks.java`
- `ref/1.20.1/Create/src/main/java/com/simibubi/create/content/equipment/wrench/IWrenchable.java`
- `ref/1.20.1/Create/src/main/java/com/simibubi/create/content/equipment/wrench/WrenchItem.java`
- `ref/1.20.1/Create/src/main/java/com/simibubi/create/content/equipment/wrench/WrenchEventHandler.java`

## 3. Create 的原始模式

Create 没有 `createWoodKinetic` 一类单体模板。它组合以下机制：

| 维度 | Create 机制 | 示例 |
| --- | --- | --- |
| 属性基线 | `SharedProperties` | `stone()` 取安山岩，`softMetal()` 取金块，`copperMetal()` 取铜块 |
| 外观与碰撞覆盖 | `.properties(...)` | 给石质齿轮覆盖木头音效，给机械设备增加 `noOcclusion()` |
| 挖掘工具 | `TagGen` | `axeOrPickaxe()`、`pickaxeOnly()`、`axeOnly()` |
| 复杂注册模板 | `BuilderTransformers` | `casing()`、`packager()` |
| 扳手 | 类继承和 `IWrenchable` | `KineticBlock -> IRotate -> IWrenchable` |
| 掉落 | loot provider 或 `.loot(...)` | 默认掉自身、掉另一方块、无掉落等 |

这几个维度不能相互替代：

- `Block.Properties.copy(...)` 不会复制数据包标签。
- `mineable/pickaxe` 只说明工具类型，不会自动增加 `requiresCorrectToolForDrops()`。
- `needs_diamond_tool` 只说明工具等级，不会自动把方块加入镐标签。
- 实现 `IWrenchable` 不会自动解决自定义 `use()` 抢先消费右键的问题。
- 属性和标签一致也不代表 loot table 已经与对应原版方块一致。

## 4. 本项目的目标架构

### 4.1 属性层：`CBSharedProperties`

新增 `registry/CBSharedProperties.java`。每个方法必须返回一份新的属性对象，不缓存和复用可变的 `Block.Properties`。

建议只定义材料基线，不在方法名中混入挖掘标签或扳手语义：

| 方法 | 基线 | 用途 |
| --- | --- | --- |
| `createWooden()` | `SharedProperties.wooden()` | 水车式木质机械、Butter Cat |
| `createStone()` | `SharedProperties.stone()` | 轴、齿轮、离合器、安山机械 |
| `createSoftMetal()` | `SharedProperties.softMetal()` | 打包机、物流端口、列车控制式设备 |
| `createCopperMetal()` | `SharedProperties.copperMetal()` | 泵、打印机和流体设备 |
| `enchantingTable()` | `Blocks.ENCHANTING_TABLE` | 唤魔者附魔室 |
| `buddingExperience()` | `Blocks.BUDDING_AMETHYST` | 经验母岩 |
| `smallExperienceBud()` | `Blocks.SMALL_AMETHYST_BUD` | 小型经验芽 |
| `mediumExperienceBud()` | `Blocks.MEDIUM_AMETHYST_BUD` | 中型经验芽 |
| `largeExperienceBud()` | `Blocks.LARGE_AMETHYST_BUD` | 大型经验芽 |
| `experienceCluster()` | `Blocks.AMETHYST_CLUSTER` | 完整经验簇 |
| `vanillaGlass()` | `Blocks.GLASS` | 防爆玻璃的玻璃行为基线 |
| `withObsidianDurability(properties)` | 覆盖为黑曜石的 50 硬度、1200 爆炸抗性和正确工具要求 | 所有防爆系列方块 |

示意代码：

```java
public static Block.Properties createStone() {
    return Block.Properties.copy(SharedProperties.stone());
}

public static Block.Properties createSoftMetal() {
    return Block.Properties.copy(SharedProperties.softMetal());
}

public static Block.Properties enchantingTable() {
    return Block.Properties.copy(Blocks.ENCHANTING_TABLE);
}

public static Block.Properties buddingExperience() {
    return Block.Properties.copy(Blocks.BUDDING_AMETHYST);
}

public static Block.Properties withObsidianDurability(Block.Properties properties) {
    return properties.strength(50.0f, 1200.0f)
        .requiresCorrectToolForDrops();
}
```

具体音效、地图颜色、透明、发光和抗爆性仍留在注册项附近覆盖，保持 Create 的写法和可读性。

### 4.2 标签层：数据生成作为唯一来源

新增 Forge datagen：

- `data/CBDataGenerators.java`：监听 `GatherDataEvent`。
- `data/CBBlockTagsProvider.java`：生成方块标签。
- `build.gradle`：把 `src/generated/resources` 接入 `sourceSets.main.resources`。

生成以下原版标签：

- `minecraft:mineable/axe`
- `minecraft:mineable/pickaxe`
- `minecraft:needs_diamond_tool`

主注册表仍是 `DeferredRegister`，因此不能直接使用面向 Registrate `BlockBuilder` 的 `TagGen.axeOrPickaxe()`。`CBBlockTagsProvider` 应复刻它的最小语义：把同一组方块同时加入斧和镐标签，不复制 Create 的内部生成框架。

Butter Cat 已迁移到主 `DeferredRegister`，两种动力方块也由同一个 `CBBlockTagsProvider` 加入 `mineable/pickaxe`。因此最终工具标签只有一个 provider/输出路径，不再由 Registrate `TagGen` 和 Forge provider 同时写入同一 JSON。

datagen 接入完成后，删除当前手写的三个工具标签 JSON，让生成代码成为唯一来源。生成结果可以继续提交到仓库，但 CI 应执行 datagen 后检查工作区无差异。

### 4.3 工具策略

标签提供器内部采用以下五种策略即可，不建立复杂通用框架：

| 策略 | 标签 |
| --- | --- |
| `NONE` | 不加入工具标签 |
| `AXE` | `mineable/axe` |
| `PICKAXE` | `mineable/pickaxe` |
| `AXE_OR_PICKAXE` | 同时加入斧和镐 |
| `DIAMOND_PICKAXE` | 加入镐和 `needs_diamond_tool` |

是否需要正确工具掉落由属性基线决定。硬质防爆方块必须同时满足：

```text
requiresCorrectToolForDrops
+ mineable/pickaxe
+ needs_diamond_tool
```

### 4.4 扳手层

优先复用 Create 类层级，不建立自定义的第二套旋转系统：

| 策略 | 实现方式 |
| --- | --- |
| `KINETIC_DEFAULT` | 继承 `KineticBlock` 体系，使用默认普通旋转和潜行拆除 |
| `STANDARD_DIRECTIONAL` | 非动力有向方块实现 `IWrenchable`，复用默认旋转 |
| `CUSTOM_DIRECTIONAL` | `AllayPort` 一类特殊方向规则覆盖 `onWrenched()` |
| `MULTIBLOCK` | 点击任意组成部分都路由到控制器，原子更新整个结构 |
| `CASING` | 普通扳手不旋转；潜行拆除保留 Create casing 语义 |
| `NONE` | 自然生长、玻璃等不应旋转的方块 |

增加一个很小的 `CBWrenchHelper.isWrench(ItemStack)`，同时识别 Create 扳手本体和 `forge:tools/wrench`：

```java
return AllItems.WRENCH.isIn(stack)
    || AllTags.AllItemTags.WRENCH.matches(stack.getItem());
```

所有同时具有业务交互和扳手行为的 `use()` 必须遵循同一顺序：

1. 先读取玩家手中物品。
2. 如果是扳手，立即返回 `PASS`，让 Create 继续执行 `IWrenchable`。
3. 再处理客户端预测返回值。
4. 最后处理 GUI、物品插入、取出和状态提示。

不能先在客户端无条件返回 `SUCCESS`，否则 Create 扳手仍会被截断。

### 4.5 掉落层

掉落必须与属性和标签一起验收：

- 默认机械方块：正确工具条件满足时掉自身。
- 经验母岩：若采用完整原版语义，则不生成自身掉落；这会改变当前可回收玩法，实施前需要确认配方和进度设计。
- 经验芽/簇：保留当前“精准采集掉自身，否则掉经验”的模组玩法，不照搬紫水晶碎片掉落。
- 防爆玻璃：外观、音效、透明和碰撞对齐玻璃；耐久与工具对齐黑曜石；正确工具破坏时直接掉落自身，不要求精准采集。
- 多方块结构：无论普通挖掘还是潜行扳手拆除，都只能掉落一次，并由控制器负责保存 BlockEntity 内容。

## 5. 完整方块映射

下表覆盖当前 53 个注册方块；16 色缓冲垫作为同策略的一组。

| 方块或分组 | 属性基线 | 工具策略 | 扳手策略 | 具体处理 |
| --- | --- | --- | --- | --- |
| `slime_belt`、`magma_belt`、`power_belt` | 保留 Create `BELT` 式羊毛音效、0.8 硬度 | `AXE_OR_PICKAXE` | `KINETIC_DEFAULT` | 补齐 Create 皮带使用的双工具标签 |
| `automatic_fish_release_machine` | `createWooden()`，保留现有地图色和透明 | `AXE_OR_PICKAXE` | Create 大型水车特殊行为 | 保持普通扳手不旋转、潜行拆除；当前总体正确 |
| `evoker_enchanting_chamber` | `enchantingTable()` | `PICKAXE` | `MULTIBLOCK` | 完整继承原版附魔台的 5 硬度、1200 爆炸抗性、正确工具要求、7 级亮度和地图色；不加入钻石等级标签。两格结构任意一半都路由到下半部分；`use()` 先放行扳手 |
| `experience_pump` | `createCopperMetal()` | `PICKAXE` | `KINETIC_DEFAULT` | 与 Create 机械泵一致；当前总体正确 |
| `budding_experience` | `buddingExperience()` | `PICKAXE` | `NONE` | 获得原版正确工具要求和 `PushReaction.DESTROY`；单独确认是否改为原版无掉落 |
| 四种经验芽/簇 | 分别复制对应原版紫水晶芽/簇 | `PICKAXE` | `NONE` | 保留每阶段原版音效、光照、`forceSolidOn` 和活塞销毁；保留自定义经验掉落 |
| `squid_printer` | `createCopperMetal()`，覆盖蓝色地图色和透明 | `PICKAXE` | `STANDARD_DIRECTIONAL` | 对齐 CEI 打印机的铜制设备语义 |
| `petri_dish` | `createStone()`，覆盖玻璃音效、1.5 硬度和透明 | `PICKAXE` | `STANDARD_DIRECTIONAL` | 将其视为“带玻璃外壳的 casing 设备”，保留镐挖掘；普通扳手旋转，潜行扳手拆除并返还方块；`use()` 必须先对扳手返回 `PASS` |
| `universal_joint`、`half_shaft` | `createStone()`，覆盖现有石音效、金属地图色 | `PICKAXE` | `KINETIC_DEFAULT` | 两个轴端方块统一为 Create 轴语义 |
| `slime_clutch` | `createStone()`，覆盖木音效、灰化土地图色和透明 | `AXE_OR_PICKAXE` | `KINETIC_DEFAULT` | 对齐 Create `CLUTCH` |
| `bone_ratchet` | `createStone()`，覆盖骨块音效和沙色 | `AXE_OR_PICKAXE` | `KINETIC_DEFAULT` | 对齐 Create 齿轮，而非使用无正确工具要求的裸属性 |
| `fixed_carrot_fishing_rod` | 保留软木质自定义属性 | `AXE` | `STANDARD_DIRECTIONAL` | 实现 `IWrenchable`；在客户端返回前排除扳手，禁止把扳手作为鱼饵 |
| `ghast_hot_air_balloon_assembly_station` | `createStone()`，保留透明和合适外观音效 | `AXE_OR_PICKAXE` | `STANDARD_DIRECTIONAL` | 对齐配方中的 Create 组装站/安山机械语义 |
| `ghast_helm` | `createSoftMetal()`，覆盖控制器外观 | `PICKAXE` | `STANDARD_DIRECTIONAL` | 对齐 Create `TRAIN_CONTROLS`；保留已有 `IWrenchable` |
| `schrodingers_cat` | `createWooden()`，覆盖羊毛音效、0.8 硬度和透明 | `AXE` | `STANDARD_DIRECTIONAL` | 作为纸箱/木质装置；新增扳手旋转朝向 |
| `spider_assembly_table`、`spider_assembly_table_cog` | `createStone()`，保留黑色地图色和透明，不覆盖音效 | `AXE_OR_PICKAXE` | `KINETIC_DEFAULT` | 两个组成方块继承安山岩/石质基线音效并采用一致工具标签；不再使用羊毛音效，也不再只有斧标签 |
| `creeper_blast_chamber` | Create casing 基线，再应用 `withObsidianDurability(...)` | `DIAMOND_PICKAXE` | `MULTIBLOCK` | 硬度、抗爆、正确工具和等级与黑曜石一致；其他音效、外观和结构语义按机壳处理；`use()` 先放行扳手 |
| `asurine_casing`、`biotech_casing` | `createStone()`，木音效 | `AXE_OR_PICKAXE` | `CASING` | 与 Create `BuilderTransformers.casing()` 一致；当前总体正确 |
| `explosion_proof_casing` | Create casing 基线，再应用 `withObsidianDurability(...)` | `DIAMOND_PICKAXE` | 结构 `CASING` | 除黑曜石耐久和挖掘要求外，对齐普通 Create casing；不再加入斧标签，只有钻石镐正确掉落 |
| `explosion_proof_item_vault` | Create `ITEM_VAULT` 基线，再应用 `withObsidianDurability(...)` | `DIAMOND_PICKAXE` | Create `ItemVaultBlock` 默认 | 保留物品保险库的外观、连接和扳手行为，但硬度、抗爆和挖掘门槛与黑曜石一致 |
| `blast_proof_glass`、`blast_proof_framed_glass` | `vanillaGlass()`/Create connected glass，再应用 `withObsidianDurability(...)` | `DIAMOND_PICKAXE` | `NONE` | 保留玻璃音效、透明、碰撞和连接纹理；钻石镐正常掉落自身，不要求精准采集；错误工具和低等级镐不掉落 |
| `blast_proof_chain_drive` | Create `ENCASED_CHAIN_DRIVE` 基线，再应用 `withObsidianDurability(...)` | `DIAMOND_PICKAXE` | 结构优先，结构外 `KINETIC_DEFAULT` | 硬度、抗爆和工具门槛对齐黑曜石；其他属性对齐链式传动箱；结构外调用 `super.updateAfterWrenched()`，普通旋转不变成 casing |
| `bio_packager`、`shulker_packager` | `createSoftMetal()`，复用 Create `packager()` 的音效、地图色、透明和非红石导体属性 | `PICKAXE` | `STANDARD_DIRECTIONAL` | 两者统一；`use()` 在取出或交换包裹前先放行扳手 |
| `shulker_teleporter` | `createStone()`，覆盖紫色地图色和透明 | `PICKAXE` | `MULTIBLOCK` | 三格任意部分使用扳手都路由控制器，避免只有顶部放行、下部打开 GUI |
| `allay_port` | `createSoftMetal()`，复用 Create `PACKAGE_FROGPORT` 外观属性 | `PICKAXE` | `CUSTOM_DIRECTIONAL` | 保留自定义反向旋转；调用 BE GUI 前先放行扳手 |
| 16 色 `buffer_pad` | 保留羊毛音效、0.4 硬度 | `NONE` | 现有 `WrenchableDirectionalBlock` | 作为软质垫块，不强加斧/镐；当前扳手行为保持 |
| `cute_cat_on_shaft`、`butter_cat_engine` | Create `SHAFT`：`CBSharedProperties.createStone()`、金属地图色和轴式实体属性；不覆盖羊毛音效 | `PICKAXE` | `KINETIC_DEFAULT` | 两者与传动轴使用相同属性，并由 `CBBlockTagsProvider` 加入镐标签；只保留模型渲染确实需要的 `noOcclusion()`；将扳手分支的 `FAIL` 改为 `PASS` |

## 6. 扳手专项改造清单

### 6.1 必须修复的右键拦截

| 类 | 当前问题 | 目标行为 |
| --- | --- | --- |
| `CreeperBlastChamberBlock` | 状态提示 `use()` 消费普通右键，使 `onWrenched()` 难以到达 | 扳手先放行；结构处理统一进入 `onWrenched()` |
| `AllayPortBlock` | 站立右键优先打开物流端口 GUI | 扳手先放行，其他物品才进入 BE `use()` |
| `ButterCatEngineBlock` | 明确对 Create 扳手返回 `FAIL` | 改为 `PASS`，复用继承的动力方块扳手逻辑 |
| `BioPackagerBlock` | 已装包裹时，扳手可能先触发取出 | 扳手始终优先于包裹交互 |
| `ShulkerTeleporterBlock` | 顶部 `PASS`，中下部打开 GUI，三格行为不一致 | 三格统一代理给结构控制器 |
| `FixedCarrotFishingRodBlock` | 客户端先返回 `SUCCESS`，服务端可把任意非空物品作为鱼饵 | 在客户端分支前识别扳手；扳手不进入鱼饵槽 |

`ShulkerPackagerBlock` 已经检查 Create 扳手并返回 `PASS`，其顺序应作为其他交互方块的参考。

### 6.2 多方块扳手约束

多方块结构必须满足：

- 点击任意组成部分得到相同结果。
- 普通扳手旋转时一次性验证目标空间，再原子更新所有组成部分。
- 更新时保留控制器 BlockEntity 和库存，不先销毁再重建内容。
- 潜行扳手只产生一个物品掉落。
- 无法旋转时返回 `PASS`，不产生半旋转结构。
- 客户端只预测 `SUCCESS`，最终状态由服务端写入。

### 6.3 防爆链式传动箱

当前 `BlastProofChainDriveBlock.updateAfterWrenched()` 无条件返回 `EXPLOSION_PROOF_CASING`。按照 Create `ChainDriveBlock` 的逻辑，普通扳手应旋转并重新计算链连接，不应在结构外变成 casing。

目标规则：

1. 若属于有效爆炸室结构，先执行结构专用处理。
2. 结构逻辑消费操作时直接返回。
3. 结构外调用 `super.onWrenched()`。
4. `updateAfterWrenched()` 结构外返回 `super.updateAfterWrenched(...)`。
5. 只有明确的“拆除传动箱并还原 casing”操作才允许转换方块，不能复用普通旋转入口。

## 7. 掉落与兼容性决策

### 7.1 经验母岩

当前 `budding_experience` loot table 会直接掉落自身，而原版紫水晶母岩没有正常掉落。建议把这个行为作为唯一需要玩法确认的迁移开关：

- 严格原版模式：删除自身掉落，创造模式或命令获取。
- 保留现有进度模式：继续掉自身，但仍复制原版硬度、正确工具和活塞销毁属性。

默认实施顺序采用“先保留现有掉落”，避免在工具一致性改造中顺带改变资源循环；后续单独决定是否启用严格原版模式。

### 7.2 防爆玻璃

防爆玻璃采用“玻璃外观与物理表现 + 黑曜石耐久与工具门槛 + 自身正常掉落”的组合规则：

- 属性首先复制原版玻璃或 Create connected glass，保留玻璃音效、透明、非完整方块判定和连接纹理行为。
- 随后覆盖为黑曜石的 50 硬度、1200 爆炸抗性和 `requiresCorrectToolForDrops()`。
- 同时加入 `mineable/pickaxe` 与 `needs_diamond_tool`。
- 保留当前 loot table 直接掉落自身的规则；现有两个文件都没有精准采集条件，不需要改成原版玻璃 loot。
- 钻石或更高等级镐正常掉落自身；错误工具和低等级镐不掉落。

因此它不会表现为普通玻璃的“未精准采集即破碎”，也不会因为继承黑曜石耐久而失去玻璃渲染特征。

### 7.3 版本与存档

属性和标签变化不会更改方块 ID 或 BlockState，存档兼容风险低。以下改动会影响玩家体验，需要写入更新日志：

- 全部防爆系列统一需要钻石镐并能按各自 loot 规则正常掉落。
- 打包机、端口和部分机械方块的推荐工具改变。
- 扳手不再触发 GUI、取包或插入鱼饵。
- 若最终采用严格原版模式，经验母岩不再可回收。

## 8. 文件级实施清单

### 新增

- `src/main/java/com/nobodiiiii/createbiotech/registry/CBSharedProperties.java`
- `src/main/java/com/nobodiiiii/createbiotech/data/CBDataGenerators.java`
- `src/main/java/com/nobodiiiii/createbiotech/data/CBBlockTagsProvider.java`
- 可选：`src/main/java/com/nobodiiiii/createbiotech/foundation/block/CBWrenchHelper.java`

### 修改

- `src/main/java/com/nobodiiiii/createbiotech/registry/CBBlocks.java`
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBBlocks.java`
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBItems.java`
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBFluids.java`
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBBlockEntityTypes.java`
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBMobEffects.java`
- `src/main/java/com/nobodiiiii/createbiotech/registry/CBPotions.java`
- 表 6.1 中列出的六个交互方块类
- `EvokerEnchantingChamberBlock` 和 `ShulkerTeleporterBlock` 的多方块扳手处理
- `SchrodingersCatBlock`、`GhastHotAirBalloonAssemblyStationBlock`、`FixedCarrotFishingRodBlock` 的 `IWrenchable` 支持
- `BlastProofChainDriveBlock` 的结构外 `updateAfterWrenched()`
- `build.gradle` 的生成资源 source set

### 明确保留

- `src/main/resources/data/create_biotech/loot_tables/blocks/blast_proof_glass.json`
- `src/main/resources/data/create_biotech/loot_tables/blocks/blast_proof_framed_glass.json`

这两个 loot table 当前已经直接掉落自身且没有精准采集条件。实施时保留该语义，只通过黑曜石属性和工具标签限制“正确工具”与掉落资格。

### 由 datagen 取代

- `src/main/resources/data/minecraft/tags/blocks/mineable/axe.json`
- `src/main/resources/data/minecraft/tags/blocks/mineable/pickaxe.json`
- `src/main/resources/data/minecraft/tags/blocks/needs_diamond_tool.json`

删除手写文件前，必须先确认生成输出包含完全相同或有意调整后的集合。

## 9. 分阶段实施顺序

### 阶段 A：修复确定性缺陷

1. 给全部防爆系列增加镐及钻石等级标签，移除防爆 casing 的斧标签。
2. 修复六个 `use()` 的扳手拦截。
3. 修复防爆链式传动箱结构外旋转后变 casing 的问题。
4. 统一 Shulker 传送机多方块扳手入口。

### 阶段 B：属性基线收敛

1. 新增 `CBSharedProperties`。
2. 唤魔者附魔室复制原版附魔台属性和工具规则。
3. 迁移轴、齿轮、离合器、泵、打包机、端口和控制器。
4. 防爆方块先复制对应 Create 机壳/设备或玻璃，再统一应用黑曜石耐久属性。
5. 经验生长方块分别复制原版各阶段属性。
6. 保留每个注册项必要的外观覆盖，不在材料工厂中塞入无关功能特例。

### 阶段 C：标签生成

1. 接入 Forge datagen 和生成资源 source set。
2. 建立 `CBBlockTagsProvider`。
3. Butter Cat 两种方块由 `CBBlockTagsProvider` 使用与传动轴相同的镐策略。
4. 运行 datagen，检查差异后移除手写工具标签。

### 阶段 D：非动力有向方块扳手一致性

1. 给薛定谔的猫、恶魂装配站和固定胡萝卜钓竿加入标准扳手旋转。
2. 明确培养皿普通扳手旋转、潜行扳手拆除，并验证业务 `use()` 不拦截扳手。
3. 为 Evoker 附魔室实现完整的双格结构旋转和拆除。
4. 检查所有带 `FACING`、`HORIZONTAL_FACING` 或 `AXIS` 的本模组方块是否已明确选择扳手策略。

### 阶段 E：掉落决策

1. 保留经验芽/簇的自定义经验规则。
2. 保留两种防爆玻璃当前直接掉落自身的 loot table，并验证黑曜石工具门槛生效且不需要精准采集。
3. 单独决定经验母岩是否采用原版无掉落规则。

每一阶段单独提交，避免工具标签、方块属性、右键交互和玩法掉落同时出现在一个难以回归的提交中。

## 10. 验证方案

### 10.1 静态验证

- `./gradlew compileJava`
- 运行 datagen 后检查生成标签包含所有注册 ID，且不存在拼写错误或未注册 ID。
- `./gradlew processResources` 或 `./gradlew build`
- 检查生成的 JAR 中只存在一份最终工具标签来源。
- 搜索所有自定义 `use()`，确认扳手判断位于客户端 `SUCCESS` 和业务交互之前。

本改造不涉及 Mixin，按照项目规则不默认运行 `quickPlayClient`。

### 10.2 手工挖掘矩阵

每个策略至少抽测一个代表方块：

| 测试 | 预期 |
| --- | --- |
| 空手 | 可破坏不代表一定掉落；结果符合属性的正确工具要求 |
| 木斧、铁斧 | 只对 `AXE` 或 `AXE_OR_PICKAXE` 获得正确速度 |
| 木镐、铁镐 | 对普通镐策略可挖掘；是否掉落取决于属性和等级标签 |
| 钻石镐 | 全部防爆系列正常掉落，包括两种防爆玻璃 |
| 钻石斧 | 所有防爆系列都不视为正确工具，不掉落 |
| 精准采集镐 | 防爆玻璃无须精准采集也掉自身；经验簇继续按自定义精准采集规则掉落 |
| 活塞推动 | 经验母岩和经验簇被销毁，不被移动 |

### 10.3 扳手矩阵

同时测试 Create 扳手本体和一个加入 `forge:tools/wrench` 的兼容扳手：

| 场景 | 普通右键 | 潜行右键 |
| --- | --- | --- |
| 空动力方块 | 旋转并播放声音 | 拆除并返还方块 |
| 运行中的动力方块 | 合法时旋转并更新网络 | 安全拆除、网络刷新 |
| 已装物品的打包机 | 旋转，不取出包裹 | 拆除时内容按 loot/BE 规则保存或掉落 |
| Allay Port | 执行自定义方向切换，不打开 GUI | 拆除，不打开 GUI |
| 培养皿 | 旋转，不触发培养皿业务交互 | 拆除并返还培养皿 |
| 固定钓竿 | 旋转，不插入扳手 | 拆除，并正确处理已有鱼饵 |
| Evoker 附魔室上下两格 | 两格一致旋转 | 只掉一次，不残留半格 |
| Shulker 传送机上中下三格 | 三处结果一致 | 只掉一次，不残留结构 |
| 爆炸室结构内 | 执行结构专用逻辑 | 结构完整、安全拆除 |
| 防爆链式传动箱结构外 | 保持方块类型并旋转 | 拆除，不变成 casing |
| Butter Cat 已装面包/未装面包 | 都进入扳手逻辑 | 都按 Create 动力方块拆除 |

## 11. 验收标准

- [ ] 每个注册方块都在映射表中拥有明确的属性、工具、扳手和掉落策略。
- [ ] 不存在 `requiresCorrectToolForDrops()` 但没有任何正确工具类型的方块。
- [ ] 所有防爆系列同时位于镐和 `needs_diamond_tool` 标签，且不位于斧标签。
- [ ] 唤魔者附魔室复制原版附魔台属性，位于镐标签且不位于任何工具等级标签。
- [ ] Create 对应方块优先使用 `SharedProperties` 基线，不重复手写其硬度、抗性和正确工具属性。
- [ ] 紫水晶对应方块复制各自原版阶段，而不是共用一个不完整的 `clusterProperties()`。
- [ ] 所有实现或继承 `IWrenchable` 且具有自定义 `use()` 的方块都先放行扳手。
- [ ] 多方块结构从任意组成部分使用扳手时结果一致，且只掉落一次。
- [ ] 防爆链式传动箱结构外普通旋转不会变成 casing。
- [ ] 两种 Butter Cat 方块使用与 Create 传动轴相同的属性，并且只由 `CBBlockTagsProvider` 写入镐标签。
- [ ] 蜘蛛装配台及齿轮不再覆盖羊毛音效。
- [ ] 培养皿普通扳手可旋转、潜行扳手可拆除，且业务交互不会抢先消费扳手。
- [ ] 两种防爆玻璃使用钻石镐可直接掉落自身，不需要精准采集。
- [ ] datagen 可以稳定重建工具标签，生成后仓库无未预期差异。
- [ ] `compileJava`、资源处理和构建通过。

## 12. 明确不做的事项

- 不把整个 `CBBlocks` 从 `DeferredRegister` 迁移到 Registrate。
- 不为工具标签建立运行时动态逻辑；工具判断继续使用原版数据标签。
- 不复制 Create 的整个 datagen 基础设施，只实现本模组需要的最小 provider。
- 不把扳手行为绑定到材料属性模板。
- 不在本次改造中更改方块 ID、BlockState 属性名或网络协议。
- 不默认改变经验母岩的现有掉落；严格原版掉落作为独立玩法决策。

## 13. 实施记录（2026-08-03）

- 方块属性、工具标签、掉落与扳手改造已经落地；`schrodingers_cat` 的显式硬度已补为 `0.8`。
- `bone_ratchet` 已补入最终 `mineable/pickaxe`，并保留在 `mineable/axe`，符合 `AXE_OR_PICKAXE` 策略。
- Butter Cat 的 Registrate 注册链已经删除，原有 ID 由主 `registry` 的 DeferredRegister 保持；`content/buttercat` 静态扫描不再包含注册调用或注册对象。
- 工具标签只由 `CBBlockTagsProvider` 生成；datagen 缓存和最终 JAR 中的 `mineable/pickaxe.json` 均只有一个输出来源/条目。
- 已通过 `compileJava`、`runData` 和 `build`；最终 JAR 为 `build/libs/create_biotech-1.20.1-1.2.2.jar`。
- 本次不涉及新的 Mixin，按项目规则未运行 `quickPlayClient`；第 10.2、10.3 节的游戏内手工矩阵仍需人工回归。
