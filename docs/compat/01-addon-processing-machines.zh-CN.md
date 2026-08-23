# 01. 机械动力附属加工机械调研（1.21.1）

本文只记录从 `ref/` 本地参考源码直接核对得到的事实，用于支撑
[02-processing-compat-plan.zh-CN.md](02-processing-compat-plan.zh-CN.md) 中的方案。
未核对的推测会显式标注为“待验证”。

调研日期：2026-08-23。本仓库基线：Minecraft 1.21.1 / NeoForge 21.1.234 / Create 6.0.10-281
（见 [gradle.properties](../../gradle.properties)）。

---

## 1. 调研范围与参考基准

| 附属 | modid | 本地参考路径 | 分支 | 解析到的提交 | 源码声明版本 | 已发布最新版（Modrinth） | 版本吻合度 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 冶金学 Create: Metallurgy | `createmetallurgy` | `ref/1.21.1/Create-Metallurgy` | `mc1.21.1/dev` | `89a6993968a18503ff74256db0ecedb76dac64e9` | `1.0.4` | `1.0.3-1.21.1`（2026-05-22） | 开发分支快照，比已发布版新，非发布版精确对应 |
| CCA Create Crafts & Additions | `createaddition` | `ref/1.21.1/createaddition` | `1.21.1` | `ca6785afb2cbff814f5eb5a0e55f3d55b1d56ff9` | `1.7.0` | `neoforge-1.21.1-1.7.0`（2026-08-16） | 分支头与最新发布同日，基本吻合 |
| 电气时代 Create: New Age | `create_new_age` | `ref/1.21.1/create-new-age` | `1.21.1`（GitLab） | `01fe72c88903bf3aa5caf5d09bc740adb0b9ce44` | `1.2.1` | `1.2.0+mc1.21.1`（2026-06-08） | 开发分支快照，比已发布版新 |
| Vintage Improvements | `vintageimprovements` | `ref/1.20.1/Create-Vintage-Improvements` | `1.20.1` | `3ecf11cb9455c28e0a81569cbe9431f1b2250f7a` | `1.20.1-0.2.0.0` | `1.20.1-0.2.0.3`（2024-08-25） | **没有 1.21.1 版本**，仅供设计参考 |

各附属自身声明的依赖：

| 附属 | Create | NeoForge | Registrate | JEI |
| --- | --- | --- | --- | --- |
| `createmetallurgy` | `6.0.10-280`，范围 `[6.0.9,6.1.0)` | `21.1.228` | `MC1.21-1.3.0+67` | `19.27.0.336` |
| `createaddition` | `6.0.10-280` | 未在 `gradle.properties` 显式声明 | `MC1.21-1.3.0+67` | `19.25.0.323` |
| `create_new_age` | `6.0.10-280` | `21.1.219` | `MC1.21-1.3.0+67` | `19.21.0.247` |
| `vintageimprovements` | `0.5.1.e-22`（1.20.1 Forge） | — | — | — |

三个 1.21.1 附属与本仓库处在同一 Create 6.0.10 世代，`ProcessingRecipe` 编解码格式一致，
这是后面所有“纯数据兼容”结论成立的前提。

**Vintage Improvements 的 1.21.1 缺口需明确**：Modrinth 项目 `create-vintage-improvements` 的
`game_versions` 只有 1.18.2 / 1.19.2 / 1.20.1；上游 GitHub 仓库只有 `1.18.2`、`1.19.2`、`1.20.1`
三个分支且无任何 tag；列出的 13 个 fork 默认分支全部是 `1.20.1`。因此本文对 VI 的所有描述
都来自 1.20.1 Forge 代码，只能作为**设计模式参考**，其配方类型 ID 与 JSON 字段在未来的
1.21.1 移植版中可能变化。

---

## 2. 通用协议层：四家共用的接入点

调研中最重要的结论是，这四家附属并没有各自发明接入方式，而是共享同一组 Create 侧协议。
只要沿这几条协议走，本仓库“支持别家加工机械”几乎不需要编译期依赖。

### 2.1 配方类型即协议

四家的加工配方全部继承 Create 的 `ProcessingRecipe` / `StandardProcessingRecipe`：

- `createaddition:rolling`：`ref/1.21.1/createaddition/src/main/java/com/mrh0/createaddition/recipe/rolling/RollingRecipe.java:14`
  → `extends StandardProcessingRecipe<RecipeWrapper>`
- `createmetallurgy:grinding`：`ref/1.21.1/Create-Metallurgy/src/main/java/fr/lucreeper74/createmetallurgy/content/blocks/belt_grinder/GrindingRecipe.java:23`
  → `extends StandardProcessingRecipe<RecipeWrapper> implements IAssemblyRecipe`
- `create_new_age:energising`：`ref/1.21.1/create-new-age/common/src/main/java/org/antarcticgardens/cna/content/energising/recipe/EnergisingRecipe.java:28`
  → `extends ProcessingRecipe<RecipeWrapper, EnergisingRecipeParams> implements IAssemblyRecipe`
- VI 的 13 个类型：`ref/1.20.1/Create-Vintage-Improvements/src/main/java/com/negodya1/vintageimprovements/VintageRecipes.java:83`
  → 统一用 `ProcessingRecipeSerializer<>(processingFactory)`

这意味着**任何模组都可以在自己的 `data/<自己的命名空间>/recipe/` 下直接写 `"type": "<别家的配方 ID>"`，
不需要 classpath 依赖，也不需要任何 Java 代码**。配方文件所在的命名空间与配方类型的命名空间无关。

### 2.2 自定义 Ingredient 可以穿透到别家机器

`ref/1.21.1/Create/src/main/java/com/simibubi/create/content/processing/recipe/ProcessingRecipeParams.java:45`：

```java
Codec.either(CreateCodecs.SIZED_FLUID_INGREDIENT, Ingredient.CODEC).listOf().fieldOf("ingredients")
```

`Ingredient.CODEC` 在 NeoForge 1.21.1 上支持按 `type` 字段派发自定义 `IngredientType`。
本仓库已经注册了 `create_biotech:captured_entity_box`
（[CBIngredients.java](../../src/main/java/com/nobodiiiii/createbiotech/registry/CBIngredients.java)、
[CapturedEntityBoxIngredient.java](../../src/main/java/com/nobodiiiii/createbiotech/content/cardboardbox/CapturedEntityBoxIngredient.java)），
并已经在自家的 `create_biotech:creeper_blast_chamber_high_pressure` 里用它当输入。
该配方同样是 `ProcessingRecipe` 子类，见
[CreeperBlastChamberHighPressureRecipe.java:26](../../src/main/java/com/nobodiiiii/createbiotech/content/creeperblastchamber/CreeperBlastChamberHighPressureRecipe.java)。

**结论：同一段 `captured_entity_box` ingredient JSON 可以原样放进 `createmetallurgy:grinding`、
`createaddition:rolling`、`create_new_age:energising` 里，让别家机器直接加工“装着某种生物的纸箱”。
这是本仓库把生物接入第三方加工线成本最低的通道。**

待验证的细节：`ProcessingRecipeParams` 的网络同步用的是 `Ingredient.CONTENTS_STREAM_CODEC`
（同文件 `:102`、`:111`），客户端拿到的是解析后的 `ItemStack` 列表而非自定义 ingredient 实例。
服务端配方匹配不受影响，但客户端侧的 JEI 展示与配方书匹配会退化为 `ItemStack` 内容比较，
需要在实机中确认展示是否符合预期。

### 2.3 软依赖条件

三家 1.21.1 附属都用 NeoForge 原生条件做跨模组配方。
`ref/1.21.1/Create-Metallurgy/src/main/resources/data/createmetallurgy/recipe/compat/createaddition/tungsten_wires.json`：

```json
{
  "neoforge:conditions": [ { "type": "neoforge:mod_loaded", "modid": "createaddition" } ],
  "type": "createaddition:rolling",
  "ingredients": [ { "tag": "c:plates/tungsten" } ],
  "results": [ { "count": 2, "id": "createmetallurgy:tungsten_wire" } ]
}
```

VI 在 1.20.1 上还额外加了一层空标签保护
（`ref/1.20.1/Create-Vintage-Improvements/src/main/resources/data/vintageimprovements/recipes/rolling/aluminum_ingot.json`）：
`forge:mod_loaded` + `forge:not(forge:tag_empty)`。1.21.1 的等价写法是
`neoforge:mod_loaded` + `neoforge:not` + `neoforge:tag_empty`。当输入是 `c:` 标签时这层保护值得保留，
否则标签为空会退化成一个匹配不到任何物品的空 ingredient。

CCA 还自带一个专用条件 `createaddition:has_fluid_tag`
（`ref/1.21.1/createaddition/src/main/java/com/mrh0/createaddition/recipe/conditions/HasFluidTagCondition.java`），
说明附属自定义条件是这一层的常规做法——本仓库的 `create_biotech:feature_enabled` 属于同一类。

### 2.4 热源协议：`HEAT_LEVEL` blockstate + `BoilerHeater.REGISTRY`

Create 的 `BoilerHeater`（`ref/1.21.1/Create/src/main/java/com/simibubi/create/api/boiler/BoilerHeater.java`）
是一个公开注册表，附属把自己的方块注册进去即可提供热量；`BlazeBurnerBlock.getHeatLevelOf`
（`.../burner/BlazeBurnerBlock.java:269`）只检查 blockstate 是否带 `HEAT_LEVEL` 属性。

- 电气时代的锅炉加热器直接复用 Create 的属性对象：
  `HeaterBlock.java:22` → `public static final EnumProperty<BlazeBurnerBlock.HeatLevel> STRENGTH = BlazeBurnerBlock.HEAT_LEVEL;`
- 冶金学的工业坩埚用 Create 的注册表读热：
  `FoundryData.updateTemperature()` 逐格调用 `BoilerHeater.findHeat(level, pos, blockState)`
  （`ref/1.21.1/Create-Metallurgy/src/main/java/fr/lucreeper74/createmetallurgy/content/blocks/industrial_crucible/foundry/FoundryData.java:70`）
- 本仓库的岩浆怪燃烧室同时满足两侧：`MagmaCubeBurnerBlock` 使用 `BlazeBurnerBlock.HEAT_LEVEL` 属性，
  且 [CreateBiotech.java:115](../../src/main/java/com/nobodiiiii/createbiotech/CreateBiotech.java) 已执行
  `BoilerHeater.REGISTRY.register(CBBlocks.MAGMA_CUBE_BURNER.get(), BoilerHeater.BLAZE_BURNER)`

**结论：岩浆怪燃烧室在代码层面已经具备给冶金学工业坩埚供热的条件，无需新增代码，只差实机验证。**

### 2.5 传送带处理协议

| 机器 | 取料方式 | 证据 |
| --- | --- | --- |
| CCA 轧机 | `DirectBeltInputBehaviour` | `RollingMillBlockEntity.java:64` |
| 冶金学 砂带磨床 | `DirectBeltInputBehaviour` + `FilteringBehaviour.forRecipes()` + `ProcessingInventory` + `ItemHandler` capability | `BeltGrinderBlockEntity.java:66-79` |
| 电气时代 激发器 | `BeltProcessingBehaviour`（架在传送带 / 深板上方处理经过的物品） | `EnergiserBehaviour.java:28` |

本仓库的史莱姆传送带
（[SlimeBeltBlockEntity.java:96-102](../../src/main/java/com/nobodiiiii/createbiotech/content/slimebelt/SlimeBeltBlockEntity.java)）
同时注册了 `DirectBeltInputBehaviour` 与 `TransportedItemStackHandlerBehaviour`，
这正是 `BeltProcessingBehaviour` 需要的两个挂钩，所以激发器、磨床、轧机原则上都能接在史莱姆带上。
注意 `SlimeBeltBlock extends HorizontalKineticBlock`（`SlimeBeltBlock.java:96`）而非 Create 的 `BeltBlock`，
凡是判断“方块是不是 Create 传送带”的第三方代码都会漏掉它——这属于需要实机确认的风险点。

### 2.6 顺序装配协议 `IAssemblyRecipe`

| 配方类型 | 是否可作为顺序装配步骤 | 证据 |
| --- | --- | --- |
| `createmetallurgy:grinding` | 是（`createmetallurgy.recipe.assembly.grinding = 磨床加工`） | `GrindingRecipe.java:23` |
| `create_new_age:energising` | 是 | `EnergisingRecipe.java:28` |
| `createaddition:rolling` | **否**（1.21.1 分支） | `RollingRecipe.java` 未实现 `IAssemblyRecipe`；`RollingMillAssemblySubCategory.java` 全仓库仅在自身文件内出现，无任何引用 |
| VI 各类型 | 1.20.1 上支持（lang 中有大量 `*.recipe.assembly.*` 键） | `vintageimprovements/lang/zh_cn.json` |

CCA 的 lang 里仍保留 `createaddition.recipe.rolling.sequence = 轧制`，属于 1.20.1 遗留文案，不代表功能存在。

### 2.7 自动化忽略约定

VI 的 `VintageRecipes.shouldIgnoreInAutomation`
（`ref/1.20.1/Create-Vintage-Improvements/src/main/java/com/negodya1/vintageimprovements/VintageRecipes.java:118`）
同时识别 Create 的 `AllTags.AllRecipeSerializerTags.AUTOMATION_IGNORE` 与配方 ID 的 `_manual_only` 后缀。
本仓库已经在用 `_manual_only` 命名（`data/create_biotech/recipe/item_application/` 下 7 个文件），
与该约定天然一致。

---

## 3. 冶金学 Create: Metallurgy（`createmetallurgy`）

### 3.1 配方类型

`ref/1.21.1/Create-Metallurgy/src/main/java/fr/lucreeper74/createmetallurgy/registries/CMRecipeTypes.java`：

| 配方 ID | 中文分类名 | 承载机器 | 源码内配方数 |
| --- | --- | --- | --- |
| `createmetallurgy:melting` | 熔铸炉熔化 | 熔铸盖 `foundry_lid` | 231 |
| `createmetallurgy:alloying` | 熔铸炉熔合 | 熔铸搅拌器 `foundry_mixer` | 10 |
| `createmetallurgy:grinding` | 打磨 | **动力砂带磨床 `mechanical_belt_grinder`** | 63 |
| `createmetallurgy:bulk_melting` | 批量熔化 | 工业坩埚 `industrial_crucible` | 44 |
| `createmetallurgy:entity_melting` | 熔化实体 | 工业坩埚 | 5 |
| `createmetallurgy:casting_in_basin` | 铸造盆铸造 | 铸造盆 `casting_basin` | 27 |
| `createmetallurgy:casting_in_table` | 铸造台铸造 | 铸造台 `casting_table` | 111 |

### 3.2 动力砂带磨床（即用户所说的“动力磨床”）

- 方块实体：`BeltGrinderBlockEntity extends KineticBlockEntity implements Clearable`
- 输入：`ProcessingInventory` + `DirectBeltInputBehaviour`，并对外暴露 `Capabilities.ItemHandler.BLOCK`
- 过滤：`FilteringBehaviour(this, new BeltGrinderFilterSlot()).forRecipes()`
- 除自身 `grinding` 外还接受 Create 的 `SandPaperPolishingRecipe`（`BeltGrinderBlockEntity.java:274`），
  JEI 单独开了 `createmetallurgy.recipe.polishing_with_grinder = 磨床打磨` 分类
- 配方 JSON 是 Create 标准格式：

```json
{
  "type": "createmetallurgy:grinding",
  "ingredients": [ { "item": "minecraft:exposed_chiseled_copper" } ],
  "processing_time": 50,
  "results": [ { "id": "minecraft:chiseled_copper" } ]
}
```

- 现有 63 条配方的主题是**去氧化 / 去蜡**（把锈蚀或上蜡的铜制品磨回原状），并非“粉碎成粉”。
  这一点决定了本仓库该往磨床上挂什么语义的配方。

### 3.3 熔化实体（与本仓库主题正面相关）

`createmetallurgy:entity_melting` 用 `DamagedEntityIngredient`，可按实体类型或实体标签匹配，
并带一个 `damage` 字段：

```json
{
  "type": "createmetallurgy:entity_melting",
  "entity": { "type": "minecraft:iron_golem", "damage": 6 },
  "ingredients": [],
  "minHeatRequirement": 9,
  "results": [ { "amount": 135, "id": "createmetallurgy:molten_iron" } ]
}
```

`MobMeltingRecipe.Builder` 提供 `requireEntityTag(TagKey<EntityType<?>>, int)`，
说明按实体标签写配方是官方支持路径。这是本仓库“生物即原料”理念在别家机器上的天然落点。

### 3.4 冶金学自己的跨模组做法

`src/main/resources/data/createmetallurgy/recipe/compat/` 下 3 个文件：
- `createaddition/tungsten_wires.json`：写 `createaddition:rolling`，条件 `mod_loaded=createaddition`
- `create_enchantment_industry/experience_block.json`、`experience_nugget.json`：
  写自家 `casting_in_basin` / `casting_in_table`，输入是别家的 `create_enchantment_industry:experience` 流体

也就是说**两个方向都做**：既把自家产物塞进别家机器，也在自家机器里消费别家的流体。
另有 `compat/jade`、`compat/jei`、`compat/kubejs` 三个 Java 包。

### 3.5 材料重名警告

`createmetallurgy:graphite`（石墨）由 `CMItems.java:91` 以 `taggedIngredient` 注册进 `c:graphite`
（`src/generated/resources/data/c/tags/item/graphite.json`）。

本仓库也有 `create_biotech:graphite`，但当前**没有**加入 `c:graphite`
（`src/main/resources/data/c/tags/item/` 下只有 `buckets`、`butter`、`foods/butter`、`plates`、`plates/zinc`）。
两模组同装时会出现两种互不相认的石墨。同类风险还有冶金学的 `zinc_dust`、`tungsten_sheet`
与本仓库的 `carbon_powder`、`zinc_sheet`。

---

## 4. CCA Create Crafts & Additions（`createaddition`）

### 4.1 配方类型

`ref/1.21.1/createaddition/src/main/java/com/mrh0/createaddition/index/CARecipes.java`：

| 配方 ID | 中文分类名 | 承载机器 | 关键字段 |
| --- | --- | --- | --- |
| `createaddition:rolling` | 轧制 | **轧机 `rolling_mill`** | 标准 processing，1 进 1 出 |
| `createaddition:charging` | 充能 | 充能设备 | `energy`、`max_charge_rate` |
| `createaddition:liquid_burning` | 液体燃烧 | 液体燃烧室 | `burn_time` |

### 4.2 轧机

- `RollingMillBlockEntity` 只挂 `DirectBeltInputBehaviour`（`:64`），比磨床简单
- `RollingRecipe.getMaxInputCount() == 1`、`getMaxOutputCount() == 1`
- JSON：

```json
{
  "type": "createaddition:rolling",
  "ingredients": [ { "item": "minecraft:bamboo" } ],
  "results": [ { "id": "createaddition:straw" } ]
}
```

- 现有 16 条配方的主题是 `c:ingots/*` → 杆（rod）、`c:plates/*` → 线（wire），外加竹子 / 纸 → 吸管：

| 输入 | 输出 |
| --- | --- |
| `c:ingots/{copper,gold,iron,brass,electrum}` | `createaddition:*_rod` ×2 |
| `c:plates/{copper,gold,iron,electrum}` | `createaddition:*_wire` ×2 |
| `c:ingots/{aluminum,steel}`、`c:plates/{aluminum,steel,lead}` | Immersive Engineering 物品（带 `mod_loaded` 条件） |
| `minecraft:bamboo`、`minecraft:paper` | `createaddition:straw` |

**本仓库的 `create_biotech:zinc_sheet` 已经在 `c:plates/zinc` 与 `c:plates` 里**
（`src/main/resources/data/c/tags/item/plates/zinc.json`），语义上正好落在轧机的输入侧，
但 CCA 没有锌线成品，产物需要本仓库自己决定。

### 4.3 充能

```json
{
  "type": "createaddition:charging",
  "ingredients": [ { "item": "ae2:certus_quartz_crystal" } ],
  "results": [ { "count": 1, "id": "ae2:charged_certus_quartz_crystal" } ],
  "energy": 10000,
  "max_charge_rate": 200,
  "neoforge:conditions": [ { "type": "neoforge:mod_loaded", "modid": "ae2" } ]
}
```

### 4.4 CCA 自己的跨模组做法

- `src/main/resources/data/createaddition/recipe/compat/<modid>/`：ae2、immersiveengineering、
  jeed、mekanism、tconstruct、thermal，共 28 个文件，全部带 `neoforge:mod_loaded`
- 不适用时用 `.json.disabled` 后缀把文件“注释掉”而非删除
- 面向 Create 本体的配方直接写进 `data/create/recipe/`（`crushing` 4 个、`rolling` 2 个），
  即**占用别家命名空间**。这条不建议本仓库效仿，容易与其他模组的同名文件互相覆盖
- `gradle.properties` 里有 `mekanism_enable`、`ie_enabled`、`cc_tweaked_enable`、
  `sable_companion_enable`、`aeronautics_enable` 等编译期开关，`compat/` Java 包下有
  `computercraft`、`forge`、`jei`、`sable`、`simulated` 五个子包——CCA 把兼容当作一等公民
  （其中 sable 与 aeronautics 与本仓库共用同一套依赖）

---

## 5. 电气时代 Create: New Age（`create_new_age`）

### 5.1 配方类型

`ref/1.21.1/create-new-age/common/src/main/java/org/antarcticgardens/cna/CNARecipeTypes.java`
只注册了**一个**配方类型：

| 配方 ID | 承载机器 | 关键字段 |
| --- | --- | --- |
| `create_new_age:energising` | **激发器**（普通 / 高级 / 强化，`energiser_t1..t3`） | `energy_needed`，最多 4 个产物 |

```json
{
  "type": "create_new_age:energising",
  "energy_needed": 50000,
  "ingredients": [ { "item": "minecraft:glass_bottle" } ],
  "results": [ { "id": "minecraft:experience_bottle" } ]
}
```

### 5.2 激发器行为

- `EnergiserBehaviour extends BeltProcessingBehaviour`：架在传送带 / 深板上方，
  对经过的物品持续注入 FE，充够 `energy_needed` 后转化
- 先查 `SequencedAssemblyRecipe`，再线性遍历全部 `energising` 配方（`EnergiserBehaviour.java:44-60`），
  因此**配方条数会线性影响匹配开销**，不宜一次性灌入大量配方
- `EnergisingRecipe implements IAssemblyRecipe`，可直接作为 Create 顺序装配的一步，
  `addRequiredMachines` 指向 `BASIC_ENERGISER`

### 5.3 与本仓库主题的重合点

- 现成配方 `minecraft:glass_bottle` + 50k FE → `minecraft:experience_bottle`
  （`neoforge/src/generated/resources/data/create_new_age/recipe/energising/experience_bottle.json`），
  与本仓库的经验体系（经验泵、经验芽、经验簇、经验流体）直接重合，是最需要先对齐数值的地方
- 电气时代的热量系统（锅炉加热器、热能管道、热能泵、太阳能板、热气机）通过复用
  `BlazeBurnerBlock.HEAT_LEVEL` 与 Create 的工作盆 / 锅炉打通，本仓库的岩浆怪燃烧室走的是同一条路

### 5.4 工程结构注意

该仓库是 `common/` + `neoforge/` 多平台布局，`common` 依赖 `org.antarcticgardens.esl`（自有能量库）。
若将来需要编译期依赖，只能依赖其 NeoForge 发布产物，不能直接引用 `common` 源码。

---

## 6. Vintage Improvements（`vintageimprovements`，仅 1.20.1）

### 6.1 机器与配方类型

`ref/1.20.1/Create-Vintage-Improvements/src/main/java/com/negodya1/vintageimprovements/VintageRecipes.java:41-53`：

| 配方 ID | 中文分类名 | 机器 |
| --- | --- | --- |
| `polishing` | 打磨 | 砂带磨床 `belt_grinder` |
| `coiling` | 卷制 | 卷簧机 `spring_coiling_machine` |
| `vacuumizing` / `pressurizing` | 真空处理 / 加压处理 | 压缩机 `vacuum_chamber` |
| `vibrating` / `leaves_vibrating` | 振动处理 / 树叶振动处理 | 振动台 `vibrating_table` |
| `centrifugation` | 离心分离 | 离心机 `centrifuge` |
| `curving` | 冲压 | 冲压机 `curving_press` |
| `hammering` / `auto_smithing` / `auto_upgrade` | 锤打 / 自动锻造 | 杠杆锤 `helve_hammer` |
| `turning` | 车削 | 车床 `lathe` |
| `laser_cutting` | 激光切割 | 激光加工机 `laser` |

### 6.2 VI 的两套跨模组机制（本调研最有借鉴价值的部分）

**（a）数据层：把配方写成别家的类型。**
`data/vintageimprovements/recipes/rolling/` 下 85 个文件全部是 `"type": "createaddition:rolling"`，
条件为 `forge:mod_loaded=createaddition` + `forge:not(forge:tag_empty)`，
输入统一走 `forge:ingots/*`、`forge:plates/*` 标签。README 明确写道
“If Create Crafts & Additions installed, this craft will be replaced by rolling with Rolling Mill”，
即 **CCA 在场时用轧机路线替代自家工作台合成**。
README 列出的支持模组：Ad Astra、CC&A、TFMG、Mekanism、Tinkers' Construct、Thermal、Destroy、
Twilight Forest、Create Big Cannons。

**（b）运行时层：把已有配方再解释成机器配方。**
`VintageRecipesList.init(MinecraftServer)` 在服务器启动时扫描 `RecipeType.CRAFTING` 与
`RecipeType.SMITHING`，按形状把它们重新解释为：
- 单输入合成 → 振动台“振动解包”
- 2×2 / 3×2 特定形状 → 冲压机自动冲压（分 4 种模式）
- 全部锻造配方 → 杠杆锤自动锻造

配套 `VCRecipes` 配置提供 `allowUnpackingOnVibratingTable`、`allowAutoCurvingRecipes`、
`allowTemplatelessRecipes`、`allowSandpaperPolishingOnGrinder` 等开关。

对本仓库的含义是双向的：这种机制**不需要本仓库做任何事**就会吸收本仓库的合成配方；
若某些配方不希望被自动化，需要靠 `_manual_only` 后缀或 `create:automation_ignore` 序列化器标签排除。

---

## 7. 四家跨模组做法横向对比

| 手段 | 冶金学 | CCA | 电气时代 | VI |
| --- | --- | --- | --- | --- |
| 在自家命名空间写别家配方类型 | 是（`recipe/compat/<modid>/`） | 是（`recipe/compat/<modid>/`） | 未见 | 是（`recipes/rolling/`） |
| 占用别家命名空间写配方 | 是（`data/create/recipe/{splashing,milling,pressing}`） | 是（`data/create/recipe/{crushing,rolling}`） | 未见 | 是（`data/create/recipes/crushing`） |
| `mod_loaded` 条件 | 是 | 是 | 未见 | 是 |
| 空标签保护 | 未见 | 未见 | 未见 | 是（`tag_empty`） |
| 自定义配方条件 | 未见 | 是（`has_fluid_tag`） | 未见 | 未见 |
| 编译期可选依赖开关 | 未见 | 是（`gradle.properties` 多个 `*_enable`） | 是（`enableCCT`、`enableCraftsAndAdditions`） | 未见 |
| 运行时配方再解释 | 未见 | 未见 | 未见 | 是 |
| 配置开关控制兼容行为 | 未见 | 未见 | 未见 | 是（`VCRecipes`） |
| KubeJS 适配 | 是 | 未见 | 未见 | 未见 |

主流做法收敛得很清楚：**自家命名空间 + 别家配方类型 + `mod_loaded` 条件**，这是成本最低、
风险最小、且四家中三家都在用的路径。

---

## 8. 与 Create: Biotech 现状的交叉点

### 8.1 已经具备、无需新增代码的能力

| 能力 | 现状证据 |
| --- | --- |
| 自定义 ingredient 可进别家配方 | `CBIngredients` 注册 `create_biotech:captured_entity_box`；Create `ProcessingRecipeParams` 用 `Ingredient.CODEC` |
| 岩浆怪燃烧室可给冶金学工业坩埚供热 | `CreateBiotech.java:115` 已注册 `BoilerHeater.REGISTRY`；冶金学 `FoundryData` 用 `BoilerHeater.findHeat` |
| 史莱姆传送带可被别家机器取料 | `SlimeBeltBlockEntity` 注册了 `DirectBeltInputBehaviour` 与 `TransportedItemStackHandlerBehaviour` |
| 兼容配方可纳入功能开关 | `CBFeatureRecipeFilter.findFeatureRecipes` 扫描 `create_biotech` 命名空间下全部 `recipe/`，含未来的 `recipe/compat/` |
| 手动配方不被自动化吸收 | 已在用 `_manual_only` 后缀，与 VI 的判定一致 |
| Modrinth Maven 已配置 | [build.gradle:70](../../build.gradle) 已有 `https://api.modrinth.com/maven`，`maven.modrinth:<id>:<version>` 坐标已在用 |

### 8.2 目前缺失的部分

| 缺口 | 说明 |
| --- | --- |
| 没有任何 `recipe/compat/` 目录 | 本仓库 71 个带条件的配方全部用 `create_biotech:feature_enabled`，尚无 `neoforge:mod_loaded` 用法 |
| 石墨未进 `c:graphite` | 与 `createmetallurgy:graphite` 无法互认 |
| 碳粉无 `c:` 标签 | 与冶金学的各种 `*_dust` 无法互认 |
| 无实体标签 | 冶金学 `entity_melting` 支持按 `TagKey<EntityType<?>>` 匹配，本仓库尚未定义可供别家引用的实体标签 |
| 无兼容层功能开关 | `CBFeature` 的 30 个开关都是玩法功能，没有“兼容配方开关”这一类 |
| 史莱姆带非 `BeltBlock` 子类 | 判断“是不是 Create 传送带”的第三方代码会漏掉它，需实机确认各机器表现 |

### 8.3 材料链现状（写兼容配方时的原料清单）

来自 `src/main/resources/data/create_biotech/recipe/`：

| 链条 | 现有路径 |
| --- | --- |
| 碳 | `minecraft:coal` / `charcoal` --压碎--> `carbon_powder` --搅拌--> `graphite` --爆破室--> `minecraft:diamond` + `carbon_powder` |
| 锌 | `c:ingots/zinc` --压制--> `zinc_sheet`（已入 `c:plates/zinc`） |
| 史莱姆 | `minecraft:slime_ball` --搅拌--> `liquid_living_slime` ↔ `captured_small_slime`（压缩） |
| 黄油 | `minecraft:milk` --搅拌--> `cream` --压缩--> `butter` --顺序装配--> `super_butter` |
| 经验 | 经验芽 / 经验簇 --压碎--> `create:experience_nugget` |
| 生物机构 | 顺序装配 → `bionic_mechanism` |
| 机壳 | 原木 + `asurine_alloy` → `asurine_casing`，再 + `c:plates/zinc` → `biotech_casing` |
