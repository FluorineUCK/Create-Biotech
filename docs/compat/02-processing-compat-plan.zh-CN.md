# 02. Create: Biotech 支持其他附属加工机械的方案

配套调研见 [01-addon-processing-machines.zh-CN.md](01-addon-processing-machines.zh-CN.md)。
本文给出落地方案、目录约定、可直接复制的模板、候选清单与验证方式。
凡涉及玩法数值与产物设计的条目一律标为“待定”，需要设计确认后再写进仓库。

---

## 1. 方案结论

1. **主路线是纯数据兼容（L1）**：在 `data/create_biotech/recipe/compat/<modid>/` 下写别家的配方类型，
   用 `neoforge:mod_loaded` 做软依赖。不新增 Gradle 依赖、不新增 Java 代码、不新增 Mixin。
   冶金学、CCA、VI 三家自己都在用这条路（调研 §7）。
2. **材料互认（L2）与主路线同等优先**：把 `create_biotech:graphite` 等材料纳入 `c:` 标签，
   本身不依赖任何附属，却能一次性打通所有按标签取料的附属机器。这一步收益最高、成本最低。
3. **生物接入别家机器走已有的 `create_biotech:captured_entity_box` ingredient**，
   这是本仓库区别于普通材料附属的核心兼容能力，且已经被自家爆破室配方验证过。
4. **编译期依赖（L3）只在需要 JEI 分类补充、Jade 显示或行为级联动时才引入**，
   当前四家目标机器都不需要。
5. **Vintage Improvements 只做设计预留**：它没有 1.21.1 版本，本轮不落任何 VI 配方文件，
   只把目录约定和标签留好，等它移植后按同一模板补齐。

---

## 2. 分层方案

| 层级 | 做法 | 依赖成本 | 适用场景 | 本轮建议 |
| --- | --- | --- | --- | --- |
| L0 | 依赖既有协议，什么都不做 | 无 | 热源、传送带取料、顺序装配 | 只做验证，不改代码 |
| L1 | 在自家命名空间写别家配方类型 + `mod_loaded` 条件 | 无 | 磨床 / 轧机 / 激发器 / 熔化实体 | **主路线** |
| L2 | 补 `c:` 物品标签与实体标签 | 无 | 让别家配方自动认识本仓库材料 | **与 L1 并行** |
| L3 | `compileOnly` 引入附属 Maven，写 `compat/<modid>` Java 包 | 有 | JEI 分类补充、Jade 显示、行为联动 | 本轮不做 |
| L4 | Mixin 改别家行为 | 高 | 无法通过上面任何一层达成时 | 不建议 |

L0 的三条已经成立的能力（证据见调研 §2.4、§2.5、§8.1）：

- 岩浆怪燃烧室已注册进 `BoilerHeater.REGISTRY`，冶金学工业坩埚用同一注册表读热
- 史莱姆传送带已具备 `DirectBeltInputBehaviour` 与 `TransportedItemStackHandlerBehaviour`
- 磨床与激发器的配方实现了 `IAssemblyRecipe`，可以直接作为本仓库顺序装配链的一步

这三条都需要实机确认，但不需要写代码。

---

## 3. 目录与命名约定

```
src/main/resources/data/create_biotech/recipe/compat/
  createmetallurgy/
    grinding/<name>.json
    entity_melting/<name>.json
  createaddition/
    rolling/<name>.json
    charging/<name>.json
  create_new_age/
    energising/<name>.json
  vintageimprovements/        # 预留，VI 出 1.21.1 前保持为空
```

约定：

1. **只写自家命名空间**。不采用 CCA 与冶金学“往 `data/create/recipe/` 里塞文件”的做法，
   避免与其他模组的同名文件互相覆盖。
2. **目录名 = 目标 modid**，第二级 = 目标配方类型的 path，与 CCA、冶金学一致，便于人工定位。
3. **配方 ID 保持可读**，例如 `create_biotech:compat/createmetallurgy/grinding/graphite`。
4. **不使用 `.json.disabled` 后缀**（CCA 的做法）；本仓库已有功能开关体系，用条件表达即可。
5. 这些文件会被 `CBFeatureRecipeFilter` 一并扫描（它按 `create_biotech` 命名空间 + `recipe/` 前缀过滤），
   所以可以直接复用 `create_biotech:feature_enabled` 条件。

---

## 4. 可直接复制的配方模板

### 4.1 通用条件头

```json
"neoforge:conditions": [
  { "type": "neoforge:mod_loaded", "modid": "<目标 modid>" },
  { "type": "create_biotech:feature_enabled", "feature": "<CBFeature 序列化名>" }
]
```

输入是 `c:` 标签时，再加一层空标签保护（VI 的做法，见调研 §2.3）：

```json
{ "type": "neoforge:not", "value": { "type": "neoforge:tag_empty", "tag": "c:plates/zinc" } }
```

该写法已在 Create 6.0.10 自身的数据里核对过，`neoforge:not` / `neoforge:tag_empty` /
`neoforge:mod_loaded` 在 1.21.1 上分别出现 400 / 397 / 567 次，
样例见 `ref/1.21.1/Create/src/generated/resources/data/create/recipe/crushing/aluminum_ore.json`。
注意不要照抄 VI 1.20.1 的 `forge:` 前缀写法。

### 4.2 冶金学 · 动力砂带磨床

```json
{
  "neoforge:conditions": [
    { "type": "neoforge:mod_loaded", "modid": "createmetallurgy" },
    { "type": "create_biotech:feature_enabled", "feature": "biotechMaterials" }
  ],
  "type": "createmetallurgy:grinding",
  "ingredients": [ { "item": "create_biotech:graphite" } ],
  "processing_time": 50,
  "results": [ { "id": "create_biotech:carbon_powder", "count": 2 } ]
}
```

### 4.3 冶金学 · 熔化实体

```json
{
  "neoforge:conditions": [
    { "type": "neoforge:mod_loaded", "modid": "createmetallurgy" }
  ],
  "type": "createmetallurgy:entity_melting",
  "entity": { "type": "create_biotech:ding_dong_chicken", "damage": 4 },
  "ingredients": [],
  "minHeatRequirement": 6,
  "results": [ { "amount": 20, "id": "createmetallurgy:molten_iron" } ]
}
```

`entity` 也可以写 `{ "tag": "<实体标签>", "damage": n }`，见 `MobMeltingRecipe.Builder.requireEntityTag`。

### 4.4 CCA · 轧机

```json
{
  "neoforge:conditions": [
    { "type": "neoforge:mod_loaded", "modid": "createaddition" },
    { "type": "neoforge:not", "value": { "type": "neoforge:tag_empty", "tag": "c:plates/zinc" } }
  ],
  "type": "createaddition:rolling",
  "ingredients": [ { "tag": "c:plates/zinc" } ],
  "results": [ { "id": "<待定产物>", "count": 2 } ]
}
```

轧机是 1 进 1 出，`ingredients` 与 `results` 都只允许一项。

### 4.5 电气时代 · 激发器

```json
{
  "neoforge:conditions": [
    { "type": "neoforge:mod_loaded", "modid": "create_new_age" },
    { "type": "create_biotech:feature_enabled", "feature": "experience" }
  ],
  "type": "create_new_age:energising",
  "energy_needed": 50000,
  "ingredients": [ { "item": "create:experience_nugget" } ],
  "results": [ { "id": "<待定产物>" } ]
}
```

`energy_needed` 需与电气时代自身的 `glass_bottle → experience_bottle`（50 000 FE）对齐，
否则会出现绕过其经验瓶配方的套利路径。

### 4.6 把生物送进别家机器（本方案的关键模板）

```json
{
  "neoforge:conditions": [
    { "type": "neoforge:mod_loaded", "modid": "createmetallurgy" },
    { "type": "create_biotech:feature_enabled", "feature": "cardboardBox" }
  ],
  "type": "createmetallurgy:grinding",
  "ingredients": [
    { "type": "create_biotech:captured_entity_box", "entity": "minecraft:slime" }
  ],
  "processing_time": 100,
  "results": [ { "id": "minecraft:slime_ball", "count": 4 } ]
}
```

同一段 ingredient 可原样换到 `createaddition:rolling` 或 `create_new_age:energising`。
`CapturedEntityBoxIngredient.CODEC` 还支持 `item` / `items` 字段限定使用哪种纸箱
（默认同时接受小号与大号纸箱）。

---

## 5. 生物接入第三方机器的两条通道

| 通道 | 形态 | 适用机器 | 需要的工作 |
| --- | --- | --- | --- |
| A. 装箱后作为物品 | `create_biotech:captured_entity_box` ingredient | 任何 `ProcessingRecipe` 系机器（磨床、轧机、激发器、VI 全系） | 只写 JSON |
| B. 活体实体 | 目标模组自己的实体 ingredient | 目前仅冶金学 `entity_melting` | 只写 JSON（可选：定义实体标签） |

通道 A 的优点是通用，缺点是产物是“加工纸箱”，需要在文案与 Ponder 上说清楚纸箱会被消耗。
通道 B 更贴近本仓库“活体即原料”的理念，但只有冶金学一家支持。

**建议同时提供**：装箱路线覆盖广度，活体熔化路线覆盖冶金学专有的表现力。

---

## 6. 标签与材料互认清单（L2，最高优先级）

| 待办 | 目标标签 | 收益 | 风险 |
| --- | --- | --- | --- |
| `create_biotech:graphite` 加入 `c:graphite` | `data/c/tags/item/graphite.json` | 与 `createmetallurgy:graphite` 互认，冶金学的石墨模具链可用本仓库石墨 | 若两者获取难度差异大，会打乱冶金学配方平衡，需要先比对双方产出成本 |
| `create_biotech:carbon_powder` 归入合适的 `c:dusts/*` | 待定（`c:dusts/carbon` 或 `c:dusts/coal`） | 与冶金学粉类体系互认 | 命名需先确认社区惯例，错误的标签名不如不加 |
| 定义可被外部引用的实体标签 | 例如 `create_biotech:bionic_creatures` | 冶金学 `entity_melting` 可按标签一次覆盖一类生物 | 标签语义需稳定，一旦公开不宜再改 |
| 复核 `c:plates` / `c:plates/zinc` 现状 | 已存在 | 轧机、VI 已按 `c:plates/*` 取料 | 已有其他模组同时注册锌板时会出现重复产物 |

这一组改动**不引入任何模组依赖**，即使玩家一个附属都没装也是纯收益（有利于其他模组认识本仓库材料）。

---

## 7. 候选配方映射表

下表是候选方向，不是已确定的配方。产物、数量、耗时、能量一律标“待定”，需要设计确认。

### 7.1 冶金学 · 动力砂带磨床（`createmetallurgy:grinding`）

| 候选输入 | 语义 | 说明 |
| --- | --- | --- |
| `create_biotech:graphite` | 石墨 → 碳粉 | 提供压碎轮之外的第二条回收路径；注意磨床现有配方全是“去氧化/去蜡”，纯粉碎语义是否合适需确认 |
| 史莱姆护甲各件 | 打磨回纸板护甲 + 液态活性史莱姆 | 与 `create:filling` 的正向配方互为逆过程 |
| 装箱小史莱姆 | 磨成史莱姆球 | 通道 A 示例 |
| `create_biotech:asurine_alloy` | 表面处理 | 待定，需先确定是否存在“粗糙/精加工”两态 |

### 7.2 CCA · 轧机（`createaddition:rolling`）

| 候选输入 | 语义 | 说明 |
| --- | --- | --- |
| `c:plates/zinc`（本仓库锌板） | 锌板 → 锌线 | CCA 无锌线成品，需本仓库新增物品，属于新内容而非纯兼容，优先级低 |
| `create:shaft` → `create_biotech:half_shaft` | 轧制出半轴 | 与现有 `create:cutting` 路线并列的替代路径，改动小、语义自洽 |
| `create_biotech:bionic_mechanism` | 待定 | 生物机构是否适合被轧制需设计判断 |

注意：CCA 1.21.1 的轧制**不能**作为顺序装配步骤（调研 §2.6），
所以不要把轧机排进 `bionic_mechanism` 等顺序装配链里。

### 7.3 电气时代 · 激发器（`create_new_age:energising`）

| 候选输入 | 语义 | 说明 |
| --- | --- | --- |
| `create:experience_nugget` | 电能 → 经验形态转换 | 与本仓库经验体系耦合最紧，也最容易破坏平衡，必须先与其 50k FE 经验瓶对齐 |
| `create_biotech:incomplete_bionic_mechanism` | 电激活生物机构 | 激发器支持 `IAssemblyRecipe`，可作为 `bionic_mechanism` 顺序装配链的可选一步；主题契合度最高 |
| `create_biotech:carbon_powder` → `graphite` | 电致石墨化 | 与现有搅拌路线并列的替代路径 |
| 装箱生物 | 电击 | 通道 A 示例，产物待定 |

顺序装配链若要加入激发器步骤，必须整条配方在 `create_new_age` 缺席时仍可完成，
因此**只能新增一条带条件的完整替代配方**，不能在现有配方里插入条件步骤。

### 7.4 冶金学 · 熔化实体（`createmetallurgy:entity_melting`）

| 候选实体 | 说明 |
| --- | --- |
| `create_biotech:ding_dong_chicken` | 叮咚鸡含金属件，语义自洽 |
| `create_biotech:giant_frog` | 体量大，可给较高产出 |
| 未来的仿生生物 | 建议一次性用实体标签覆盖 |

### 7.5 Vintage Improvements（预留，等 1.21.1）

| 机器 | 与本仓库的契合点 |
| --- | --- |
| 离心机 `centrifugation` | 液态活性史莱姆、奶油分离，契合度最高 |
| 振动台 `vibrating` | 与本仓库“解包”类操作重合 |
| 卷簧机 `coiling` | 需要本仓库先有线材 |
| 砂带磨床 `polishing` | 与冶金学磨床职能重叠，两者同装时需避免重复配方 |

---

## 8. 配置与功能开关

现有 `CBFeature` 的 30 个开关都是玩法功能开关（`allayLogistics`、`biotechMaterials` 等），
语义上不适合直接拿来控制兼容配方。两种做法：

| 做法 | 优点 | 缺点 |
| --- | --- | --- |
| A. 只用 `neoforge:mod_loaded`，不加自有开关 | 最简单，与三家附属做法一致 | 玩家无法在装了附属的前提下关掉兼容配方 |
| B. 新增一类 `compat*` 开关（如 `compatCreateMetallurgy`） | 整合包可精细控制 | `CBFeature` 的每个枚举项带 `blockIds`，语义是“功能+方块”，兼容开关没有方块，需要确认该枚举是否允许无方块项（`BUFFER_PAD`、`CARDBOARD_BOX` 等已是无方块项，说明允许） |

建议先按 A 落地，等确有整合包需求再升级到 B。无论哪种，配方文件里都应保留
`create_biotech:feature_enabled` 指向对应玩法功能——例如给经验相关的兼容配方挂 `experience`，
这样玩家关掉经验体系时兼容配方会一并消失，不会留下悬空产物。

---

## 9. JEI、Ponder 与本地化影响

- **JEI 无需额外代码**：兼容配方属于目标模组的配方类型，会自动出现在目标模组自己的 JEI 分类里
  （磨床打磨、轧制、激发等）。本仓库的 `CreateBiotechJeiPlugin` 不需要改动。
- **Ponder 需要判断**：为不存在的模组写 Ponder 场景会在缺少该模组时报错，
  建议兼容内容不进 Ponder，改在物品 tooltip 或 wiki 说明。
- **本地化**：兼容配方本身不产生新的 lang 键。若为兼容新增了物品（如锌线），
  则按仓库规矩必须同时补 `en_us.json` 与 `zh_cn.json`。
- **文档**：`docs/mcmod/MOD_INTRO_MCMOD.md` 与 README 的“兼容”段落需要在配方落地后同步更新。

---

## 10. 何时需要编译期依赖（L3）

Modrinth Maven 已在 [build.gradle:70](../../build.gradle) 配置，坐标形式为 `maven.modrinth:<projectId>:<versionId>`：

| 附属 | Modrinth projectId |
| --- | --- |
| `createmetallurgy` | `Soft45xC` |
| `createaddition` | `kU1G12Nn` |
| `create_new_age` | `FTeXqI9v` |
| `vintageimprovements` | `S27aYArf`（无 1.21.1） |

只有出现以下需求时才值得引入 `compileOnly` 依赖：

1. 需要给别家机器补 Jade 显示（参考仓库现有 `compat/jade/ButterCatJadePlugin`）
2. 需要在别家 JEI 分类里追加本仓库的自定义渲染
3. 需要读别家的方块实体状态做行为联动

单纯为了写配方**不需要**任何依赖。引入依赖同时要在 `neoforge.mods.toml` 里补 optional 依赖声明，
并确认 `compileOnly` 不会把附属带进发布 jar。

---

## 11. 风险与已知坑

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 参考源码非发布版 | 冶金学与电气时代的本地 checkout 是开发分支，比已发布版新 | 配方落地前用实机（对应发布版 jar）复核配方类型 ID 与字段名 |
| 自定义 ingredient 的客户端表现 | `Ingredient.CONTENTS_STREAM_CODEC` 只同步解析后的 ItemStack | 实机确认 JEI 里“装箱生物”条目的显示与点击行为 |
| 史莱姆带非 `BeltBlock` 子类 | 第三方若按类型判断传送带会漏掉它 | 逐机器实机验证：磨床、轧机各自的 `DirectBeltInputBehaviour` 取料，激发器的带上处理 |
| 材料标签冲突 | 加入 `c:graphite` 后两种石墨互相替代，可能绕过某一方的产线难度 | 先比对双方获取成本，必要时只做单向（本仓库石墨进标签，但自家配方仍指定具体物品） |
| VI 运行时配方再解释 | VI 若移植到 1.21.1，会自动把本仓库的合成配方转成振动台 / 冲压机 / 杠杆锤配方 | 不希望被自动化的配方沿用 `_manual_only` 后缀或加 `create:automation_ignore` 序列化器标签 |
| 激发器线性遍历配方 | `EnergiserBehaviour` 每次匹配遍历全部 `energising` 配方 | 控制新增条数在个位数 |
| 双磨床重叠 | 冶金学磨床与 VI 砂带磨床职能重叠 | 若两者都写，需确认同装时不会出现重复 JEI 条目与产物歧义 |
| 平衡外溢 | 兼容配方常成为绕过原有产线的捷径 | 每条配方都要回答“它是否让某条既有产线失去意义” |

---

## 12. 验证方式

1. **构建**：`./gradlew build`。纯 JSON 改动不触发 Mixin 重映射，按仓库规矩**不需要**跑
   `quickPlaySmoke`（该任务仅用于 Mixin 相关改动）。
2. **数据校验**：启动开发客户端后检查日志有无配方解析错误；
   条件不满足时配方应静默消失而不是报错。
3. **缺模组场景**：在不装任何附属的实例里进入世界，确认 `recipe/compat/` 全部静默失效，
   JEI 无残留条目。
4. **装模组场景**：向 `.minecraft/versions/1.21.1-NeoForge/mods` 放入对应附属 jar
   （冶金学 `1.0.3-1.21.1`、CCA `neoforge-1.21.1-1.7.0`、电气时代 `1.2.0+mc1.21.1`），
   逐条确认 JEI 分类、实机加工与产物。
5. **L0 三项专项验证**（不需要写代码，但必须实测）：
   - 岩浆怪燃烧室置于冶金学工业坩埚下方，确认 `getCurrentHeat()` 有读数
   - 磨床 / 轧机 从史莱姆传送带取料
   - 激发器架在史莱姆传送带上方处理物品

---

## 13. 分期任务清单

### 第一期：零依赖收益（建议先做）

- [ ] `create_biotech:graphite` 加入 `c:graphite`
- [ ] 确认 `carbon_powder` 的 `c:dusts/*` 命名并落标签
- [ ] 建立 `data/create_biotech/recipe/compat/` 目录约定与本文的模板文件
- [ ] 实机验证 L0 三项（热源、传送带取料、带上处理）

### 第二期：冶金学与电气时代

- [ ] 确定磨床候选配方的产物与耗时（设计确认）
- [ ] 确定激发器候选配方的能量数值，与其 50k FE 经验瓶对齐（设计确认）
- [ ] 落地 `createmetallurgy:grinding`、`createmetallurgy:entity_melting` 配方
- [ ] 落地 `create_new_age:energising` 配方
- [ ] 装模组实机验证 + JEI 检查

### 第三期：CCA 与装箱生物通道

- [ ] 决定是否新增锌线一类物品（属于新内容，需单独立项）
- [ ] 落地 `createaddition:rolling` 配方
- [ ] 落地装箱生物穿透配方（通道 A），确认 JEI 显示
- [ ] 更新 README 与 mcmod 简介的兼容段落

### 第四期：Vintage Improvements（阻塞中）

- [ ] 等待 VI 发布 1.21.1
- [ ] 届时重新拉取参考源码并核对配方类型 ID 是否沿用 1.20.1 命名
- [ ] 按同一模板补齐离心机 / 振动台配方
