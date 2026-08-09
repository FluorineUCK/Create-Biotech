# Create: Biotech Mixin 全量审阅报告

状态：增量审阅中（2026-08-07）

## 1. 审阅范围与版本基线

本报告审阅 `src/main/resources/create_biotech.mixins.json`、`create_biotech_allay.mixins.json` 和 `create_biotech_sable.mixins.json` 中列出的全部 Mixin，以及这些 Mixin 直接使用的 accessor、bridge 和兼容插件。当前配置共有 73 个目标：主配置 69 个、Allay 配置 1 个、Sable 配置 3 个。`SlimeChainData` 等没有 `@Mixin` 注解的接口只作为 Mixin 注入后的运行时桥接，不单独计入目标数量。

本次审阅按当前工程声明判断：Minecraft 1.21.1、NeoForge 21.1.219、Create `6.0.10-281`、Ponder 1.0.82、Flywheel 1.0.6、JEI 19.21.0.247、MixinExtras 0.5.0。第三方实现优先核对 `ref/1.21.1/`；`ref/SOURCES.md` 说明该目录中的 Create 是官方 `mc1.21.1-6.0.10` 标签提交 `ac0c444d9828da3453ae8cc65338e8de063286fb`，与当前 Create 版本线相符。报告中的“原逻辑完整性”以该基线为准，不能自动推导到 Create 6.0.11 或未来版本。

## 2. 评价标准

- 必要性：是否必须改变目标类本身，是否存在 NeoForge/Create/JEI 公共扩展点可以承载同一行为。
- 兼容性：目标类和成员的稳定程度、版本耦合、与其他 Mixin 的排序/取消冲突、可选依赖缺失时的行为。
- 侵入性：只读 accessor、单调用包裹、尾部追加、条件取消、完整替换分别评估；目标类越基础、注入点越靠前、越容易取消原逻辑，风险越高。
- 逻辑完整性：凡是取消原方法的实现，都必须保留原方法中与目标功能无关的权限、红石、过滤器、冷却、事件、同步、掉落、失败回滚和客户端/服务端分支。
- 原版影响：对原版实体、原版方块、普通 Create 设备和未安装可选依赖的世界，默认应保持原行为；新增行为应以同一目标方法的状态判定和数据流为来源。

风险等级：P0 表示可能造成跨请求/跨实体的状态污染或数据损失；P1 表示启动、存档、核心运输/传送行为或第三方兼容的高风险问题；P2 表示可见功能损坏、版本脆弱或较高侵入性；P3 表示局部视觉、便利功能或低影响内部耦合。

## 3. 第一批结论：配置加载与 accessor/状态注入

### 3.1 Mixin 配置加载层

`create_biotech.mixins.json` 和 `create_biotech_allay.mixins.json` 都是 `required: true`，且默认 `defaultRequire: 1`。这对核心传送带、存档迁移和实体状态是合理的 fail-fast 策略，但配置中同时包含 JEI 绘制、客户端视觉和便利 UI 注入；其中任意一个目标签名变化都可能把一个本可降级的视觉功能升级为启动失败。建议将可选视觉/JEI 目标拆到独立的可选配置，或对明确可降级的注入设置 `require = 0` 并记录一次版本化警告，不能把整个主配置的 defaultRequire 全局降为 0。

Sable 配置使用 `required: false` 和 `SableMixinPlugin`，并在 `onLoad` 中按 class resource 检查完整 API。这一层隔离了 Sable/Simulated 的可选类引用，方向正确；但 `UniversalJointEndpointBlockSableMixin` 的 `@Mixin({ UniversalJointBlock.class, HalfShaftBlock.class })` 仍把两个目标绑定到同一个兼容类，任一目标结构变化都会影响该兼容 Mixin 的应用。后续应把两个目标拆成独立 Mixin，分别由插件检查目标类和接口方法，减少兼容故障的连带范围。`SimBlockMovementChecksMixin` 只在目标类存在时启用，属于低侵入的追加式兼容。

Allay Mixin 的目标是 Create 自有 `PackagerBlockEntity`，Create 又是本模组的必需依赖，因此“目标类缺失”不是可选 Allay 本身的问题。`wakeTheFrogs()V` 是无参数、纯 Create 方法名，`remap = false` 在当前版本下可以成立；但该注入位于公共包裹器的生命周期尾部，仍依赖 Create 内部方法名，建议至少保留启动 smoke test。

### 3.2 `AbstractVillagerAccessor`

目标：`AbstractVillager.offers` 私有字段。

必要性：当前交易改写需要读取和替换实例级 `MerchantOffers`，单靠 `getOffers()` 会再次触发改写，公开事件通常只覆盖职业/交易生成阶段，不能可靠覆盖已加载存档、职业变化和现有报价恢复。因此 accessor 是合理的最低侵入方案。

兼容性与侵入性：只有两个字段 accessor，不改控制流，不影响普通村民逻辑；风险集中在 Minecraft 私有字段名/类型变化。当前没有错误地写 `remap = false`，符合映射规则。可替代性为“低”：若未来 NeoForge 提供实例报价 setter 或交易刷新事件，应迁移到公开 API，否则不建议为此重写整个 `getOffers()`。

### 3.3 `MobAccessor`

目标：`Mob.persistenceRequired` 私有字段。

必要性：代码在处理高压苦力怕时先调用公开的 `setPersistenceRequired()`，之后需要恢复为非持久状态；当前目标只暴露 setter，属于状态回滚所需的窄 accessor。应在 [CreeperBlastChamberBlockEntity.java](../../src/main/java/com/nobodiiiii/createbiotech/content/creeperblastchamber/CreeperBlastChamberBlockEntity.java) 的调用链上确认实体移出腔室、死亡和异常路径都能恢复，否则 accessor 会掩盖生命周期问题。

兼容性与侵入性：只增加一个接口方法，不改变 `Mob` 的任何原逻辑，风险低于注入 `Mob` 的 tick/AI。仍然绑定 Minecraft 私有字段 `persistenceRequired`；当前未关闭 remap，保留正确的映射处理。公开 API 只有置为 true 的路径，若没有公开的 clear 方法，accessor 暂时不可替代。

### 3.4 `WalkAnimationStateAccessor` 与 `PowerBeltWalkAnimationMixin`

`WalkAnimationStateAccessor` 读取/写入 `speedOld`、`speed`、`position`，`PowerBeltWalkAnimationMixin` 在 `LivingEntity.calculateEntityAnimation(boolean)` 的 TAIL 追加动力带表面运动。它没有取消原版动画，而是基于原方法已经计算出的 `speedOld/speed/position` 做增量修正；对普通实体 `consumeSurfaceMovement` 返回空时不产生影响，逻辑同源性较好。

必要性：公开 `WalkAnimationState.update()` 只能按目标速度重算，无法读取并精确修正原方法刚刚产生的旧速度、当前位置，accessor 是比替换 `calculateEntityAnimation` 更低侵入的办法。兼容风险仍为 Minecraft 私有字段名和原算法在不同版本中的更新时机；P2。建议把修正封装为明确的“基于原值的 delta”，并补充速度为 0、负向运动、瞬时脱离动力带和实体死亡的测试。若将来可在动力带移动事件中直接提供动画速度，才有机会移除该 Mixin。

### 3.5 客户端纯 accessor：`CreeperAccessor`、`ModelPartAccessor`、`LevelRendererAccessor`、`ServerGamePacketListenerAccessor`、`StockKeeperRequestMenuAccessor`

这些类本身不注入控制流，只暴露私有字段，侵入性通常低于行为 Mixin，但每项的必要性不同：

- `CreeperAccessor` 读写 `oldSwell/swell`，用于渲染高压苦力怕的临时脉动。没有对应公开 setter；窄 accessor 合理，P2 的版本耦合，必须保证 render 的 finally 恢复。
- `ModelPartAccessor` 读 `cubes/children`，用于自定义实体模型替换。该字段是客户端模型内部结构，JEI/渲染版本变化时容易失效；没有公共遍历 API 时可保留，P2。应避免把它用于普通模型的全局修改。
- `LevelRendererAccessor` 读 `ticks`，只用于引擎动画时间取样；对游戏逻辑无影响，但可用 `AnimationTickHolder` 或客户端帧时间 API 的场景应优先迁移，P3。
- `ServerGamePacketListenerAccessor` 读 `ackBlockChangesUpTo`，用于方块放置预测序列同步。它直接接触网络确认状态，虽然只读且不改原逻辑，协议/字段变化会影响交互正确性，P1。若 NeoForge 暴露当前 block-change acknowledgement，应迁移；在此之前必须测试丢包、重复序列和跨维度放置。
- `StockKeeperRequestMenuAccessor` 写 Create 股票请求菜单的 `isAdmin/isLocked`。字段是 Create 私有实现，当前 `remap = false` 只用于纯 Create 字段名可以接受，但它把权限/锁定状态直接改到菜单实例，P2。应优先寻找 Create 的菜单构造参数、请求包 API 或可扩展权限回调；若没有，accessor 比改 `stillValid` 或网络处理更窄。

本批总体结论：accessor 并不等于“无风险”，但在确实缺少公开 API 时，它们是当前 Mixin 集合中侵入性最低的一类。应建立字段存在性和调用方测试，避免以后用行为替换来绕过这些窄接口。

## 4. 当前批次的待验证项

本批没有修改代码，因此没有运行客户端启动。需要在后续实现性修复时验证：

1. 生成的 `create_biotech.refmap.json` 是否包含所有带 Minecraft/Mojang 类型描述符的目标；
2. `CreeperRendererMixin`、`PowerBeltWalkAnimationMixin` 和网络 accessor 在服务端类加载时没有被错误触发；
3. Sable 缺失、Sable 存在但 Simulated 缺失、两者都存在三种组合均能正常启动；
4. 普通村民、普通 Mob、普通 LivingEntity 和非 Create 客户端渲染保持原行为。

## 5. 第二批：Basin、Funnel 与 Belt Surface

这一组是当前模组最核心、也是耦合最密集的一组。它同时修改了 Create 的 Basin 输出/库存边界、Funnel 的放置和实体接触，以及 `BeltFunnelBlock` 的状态定义、几何、形状判定、扳手行为和运行时传输。这里的主要设计方向是“在现有 Create Funnel/Basin 上增加实体化小史莱姆和非标准 BeltSurface”，方向本身合理；风险来自 Create 没有为这两条扩展提供完整的公共回调，因此多个 Mixin 需要接触私有方法、私有字段或原版状态机。

### 5.1 `BasinBlockEntityMixin`

目标：`BasinBlockEntity.tick()` 尾部，以及 `acceptOutputs(List<ItemStack>, List<FluidStack>, boolean)` 方法头部。

必要性：

- `tick()` 尾部调用 `CapturedSmallSlimeItem.syncInBasin`，把 Basin 中的控制物品数量和实际捕获的小史莱姆实体数量保持一致。Create 没有公开的“Basin tick 完成后”回调，也没有实体化自定义加工输出的 API，因此如果要继续使用 Create 原生 Basin 和加工流程，Mixin 是目前可行的接入点。
- `acceptOutputs` 需要把捕获史莱姆物品从普通加工输出中分离出来，写入 Basin 的输出库存，并让其他物品/流体继续走 Create 的原始输出路径。仅注册自定义 Recipe 类型不能覆盖 Create 已经编译好的 `acceptOutputs` 调用链，所以这一处行为注入具有实际必要性。

兼容性与侵入性：

- `tick` 是尾部追加，不取消 Create 的任何逻辑，普通 Basin 只增加一次快速的物品/实体计数检查。对没有捕获史莱姆的 Basin，逻辑影响主要是一次扫描和持久数据读取，属于低至中等侵入。
- `acceptOutputs` 在没有捕获物品时直接返回，让原方法完整执行；普通配方、普通物品、普通流体的原版路径因此保留。
- 一旦输出列表中出现捕获物品，Mixin 会取消原方法，并由 `BasinEntityProcessing.acceptsCapturedSmallSlimeOutput` 和递归调用 `basin.acceptOutputs(otherItems, outputFluids, ...)` 共同完成处理。递归调用的 `otherItems` 已经排除了捕获物品，因此不会重新进入该分支；这一点避免了无限递归。
- 该实现仍依赖 Create 的私有内部状态间接完成输出库存的 `allowInsertion/forbidInsertion` 切换，并依赖 `BasinInventoryMixin` 的 ThreadLocal 放行规则。两个 Mixin 必须成对工作，任何一个加载失败都可能把捕获物品变成“无法插入”或导致输出分支失败。
- 当前 `acceptOutputs` 的目标注解写了 `remap = false`，但描述符含有 `net.minecraft.world.item.ItemStack` 和 `net.neoforged.neoforge.fluids.FluidStack`。按照本工程的 NeoForge 映射约定，这类描述符仍包含需要映射的 Minecraft 类型，不能仅因为方法名属于 Create 就关闭 remap。应改为让注解处理器生成 refmap，并检查生成的目标描述符；这是独立于逻辑设计的映射风险。

原逻辑完整性与原版影响：

- 对没有捕获物品的调用，原方法完整覆盖。
- 对包含捕获物品的调用，普通物品和流体仍委托回原方法，所以 Create 的方向判断、外部 Item/Fluid Capability 检查、外部输出缓存、模拟阶段、流体强制填充和 Basin 内部输出库存逻辑都没有被复制重写。
- 但捕获物品本身不再跟随原始 `targetInv`：即使 Basin 的输出方向指向外部库存，它也会被存入 Basin 输出库存，之后由实体同步逻辑处理。这是功能需要，但应明确为玩法规则，而不是声称它完全等价于 Create 原逻辑。
- 模拟阶段和执行阶段被拆成“先模拟捕获物品，再模拟其他输出；执行时先写捕获物品，再执行其他输出”。在正常单线程 tick 中通常能工作，但它不是事务；如果其他模组在两次调用之间改变了目标库存，捕获物品可能已经写入而后续普通输出失败，Create 原始方法也没有通用回滚机制，但这里新增了跨两个存储目标的部分提交窗口。建议增加并发/重入测试，并在可行时把自定义分支改造成一次统一的预检和一次统一提交。
- `outputItems` 中的捕获物品默认被视作同一种物品并合并数量；当前谓词是按物品身份判断，前提成立。若未来把该谓词扩展为多个带不同组件的控制物品，合并逻辑会丢失组件差异，需要改成按 `ItemStack.isSameItemSameComponents` 分组。

公开 API 替代性：

当前 Create 6.0.10 没有 Basin 输出回调、输出路由策略接口或“实体化输出”的公开扩展点，不能完全用公开 API 替代。最低风险的长期方案是在 Create 侧增加 `BasinBlockEntity` 的输出处理事件/策略接口；在本模组内部，保留一个只处理捕获物品的入口并继续委托原方法，是比复制整个 `acceptOutputsInner` 更低侵入的实现。建议将 `remap` 修正和模拟/执行原子性列为优先整改项。综合等级：必要性高，侵入性中高，逻辑风险 P1。

### 5.2 `BasinInventoryMixin`

目标：`BasinInventory.insertItem`、`extractItem`，以及继承自 `SmartInventory` 的 `isItemValid`、`setStackInSlot`。

必要性：

该 Mixin 试图封住 Basin 的所有常见物品通道：外部 Capability 插入/提取、Create 内部的有效性检查和直接槽位写入。仅包装 `BasinBlockEntity.itemCapability` 不够，因为 Create 内部和部分公开调用方直接拿到 `inputInventory/outputInventory`；反过来，只在捕获时临时修改物品数量也无法阻止外部自动化把控制物品抽走。因此在当前 Create 结构下，窄范围的库存 Mixin 有实际必要性。

兼容性与侵入性：

- 注入位全部在 BasinInventory 自身，未修改所有 `IItemHandler` 或所有 `SmartInventory`，目标范围相对收敛。
- 捕获物品且不在内部移动上下文时，插入返回原栈、提取返回空、槽位写入直接忽略；普通物品完全走 `BasinInventory`/`SmartInventory` 原逻辑。
- `isItemValid` 和 `setStackInSlot` 是 Mixin 类对继承方法的覆盖，不是单点回调，因此依赖 `SmartInventory` 的方法签名和调用约定。Create 若把库存改成新的 handler 或绕过这些虚方法，保护会失效。
- `canMoveCapturedSmallSlimeItems()` 通过 ThreadLocal 深度和线程堆栈判断是否属于 Create Funnel/BasinRecipe 或本 Mixin 的内部路径。堆栈字符串是版本脆弱的隐式协议；它会把整个 `com.simibubi.create.content.logistics.funnel` 包视为可信来源，未来 Create 在该包中增加与捕获物品无关的库存搬运逻辑时，可能意外绕过保护。它也不能表达“哪个具体操作被授权”，只表达“当前调用栈看起来像可信调用”。
- 目前 `BasinEntityProcessing` 已用 `beginCapturedSlimeItemMovement/endCapturedSlimeItemMovement` 包围真正的内部写入，这是比完全依赖堆栈更稳定的部分；但 Create 原逻辑路径仍依靠包名扫描，应继续减少。

原版影响：

普通 Basin、普通物品和普通自动化保持原行为。只有新捕获物品会受到拦截，因此不会改变普通配方的槽位唯一堆叠、模拟插入顺序、`notifyChangeOfContents` 或抽取数量语义。对捕获物品而言，外部管道无法抽取是设计目标；然而外部模组若把该物品当作普通配方输入，是否允许通过 Create 的 BasinRecipe/漏斗内部路径搬运，当前由堆栈判断决定，属于隐含玩法规则，应在文档和测试中固定。

公开 API 替代性：

NeoForge 的普通 `IItemHandler` 接口没有统一的插入/提取拦截事件，Create 也没有公开 BasinInventory 的过滤策略。若只要求阻止外部 Capability，最小替代是给 Basin Capability 返回一个包装 handler；但它不能覆盖直接的 `inputInventory/outputInventory` 访问，也会改变 Create 现有 handler 组合。完整替代需要 Create 提供 Basin inventory policy，当前不可行。建议保留该 Mixin，但把可信路径从堆栈扫描收缩为显式内部 API/ThreadLocal token，并为“外部 Capability、Create Funnel、Recipe、直接槽位写入”分别建立测试。综合等级：必要性高，侵入性中高，兼容风险 P1。

### 5.3 `BasinBlockMixin`

目标：`updateEntityAfterFallOn` 和 `useItemOn`。

`updateEntityAfterFallOn` 在捕获物品实体落入 Basin 时直接取消原方法。对普通 ItemEntity 和普通 Basin，原方法完整执行；对捕获物品，则阻止 Create 原生的插入/丢弃处理，从而避免玩家把实体控制物品以掉落物形式绕过保护规则。这是与 `BasinInventoryMixin` 配套的边界保护，必要性中等，侵入性低至中等。

它仍然是整个方法的取消，而不是取消 Create 内部那一次 `ItemHandlerHelper.insertItem`。如果 Create 将来在该方法中加入掉落物归位、事件触发或其他与库存无关的逻辑，捕获物品路径会一并跳过。若 NeoForge 版本提供可取消的实体进入方块事件，事件层会比取消整个方块方法更低侵入；在没有等价事件时，应至少补充客户端/服务端、物品实体无效、Basin 被替换和水流推动物品等测试。

`useItemOn` 在“空手且 Basin 中存在捕获史莱姆或捕获物品”时取消 Create 原始清空逻辑，只把普通输入/输出物品搬给玩家，保留捕获物品，并调用 `basin.onEmptied()`。普通有手持物品、没有受保护内容的 Basin 都返回原逻辑，因此原版交互基本保持。

这里有两个需要明确的行为风险：

1. `player.getInventory().placeItemBackInInventory(stackInSlot)` 的返回结果未检查，随后无条件把槽位清空；这与 Create 原方法本身的处理方式相同，但在本分支里捕获物品使玩家更可能只清空了部分库存，建议确认是否接受满背包时的物品丢失，并考虑按剩余栈回写。
2. 受保护分支只处理两个 BasinInventory，不走 Create 的 `itemCapability`；当前 Create 6.0.10 的 `itemCapability` 正是这两个库存的组合，所以逻辑同源，但如果 Create 增加第三种物品缓冲，分支会漏掉它。

公开 API：没有专门的 Basin 清空策略回调。可以考虑在玩家右键事件阶段拦截空手交互，但需要复制 Create 的服务端/客户端返回语义，未必比当前目标方法注入更可靠。综合等级：必要性中等，侵入性中等，原版影响 P2，建议重点验证满背包和未来 Create 新增缓存。

### 5.4 `BeltFunnelBlockStateMixin`

目标：`BeltFunnelBlock.createBlockStateDefinition`，增加 `cb_attachment_surface` 六向属性。

必要性：

非标准 BeltSurface 可以让 Funnel 附着在 Belt 的侧面或其他表面。仅用 `HORIZONTAL_FACING` 无法同时记录“漏斗口方向”和“附着面方向”；把附着面放在 BlockEntity NBT 又会让方块状态、碰撞、渲染和邻居更新在 BE 尚未加载时失去必要信息。因此用 BlockState 持久化是功能上最直接的方案。

侵入性与兼容性：

- 这是对 Create 核心方块状态定义的结构性修改，所有 Create Belt Funnel 状态都会多出一个属性，状态组合数量扩大六倍左右，方块状态调色板、结构文件、Schematic 和第三方按属性枚举的代码都会受到影响。
- 默认值是 `DOWN`，普通水平 Belt Funnel 的旧语义保持不变；普通 Create、Magma 和 Power Belt 的解析器在没有 Surface Provider 时也会回退到原逻辑，这是重要的兼容设计。
- 旧存档中没有该属性的状态会依赖 Minecraft 的状态解析默认值。需要用旧世界、结构方块、Schematic、Ponder 生成状态和网络方块更新做实际验证，不能只凭默认值推断所有加载路径安全。
- 该属性名和六值集合属于本模组的存档协议。一旦发布后修改名称、方向语义或默认值，旧世界会出现漏斗方向/附着面错位，必须视为数据兼容承诺。

公开 API 替代性：Create 没有允许向已有 `BeltFunnelBlock` 动态追加属性的公共 API。注册一个完全独立的“Surface Funnel”方块可以避免改 Create 状态，但会重复 Create 的 FunnelBlockEntity、物品、扳手、渲染和物流集成，整体侵入性通常更高。当前方案不可完全优化，但必须将新增属性的状态迁移和变换测试纳入发布门槛。综合等级：必要性高，结构侵入性高，兼容风险 P1。

### 5.5 `AbstractHorizontalFunnelBlockMixin`

目标：`AbstractHorizontalFunnelBlock.rotate`，同步旋转 `ATTACHMENT_SURFACE`。

同步附着面本身是必要的，否则结构方块、Schematic 或其他调用 `BlockState.rotate` 的路径只旋转 `HORIZONTAL_FACING`，会把局部方向和附着面拆开。注入在返回值尾部，不取消原方法，且只对带新增属性的状态生效，普通 Funnel/非 Surface 状态不受影响。

但是当前实现只做了：

```text
result = Create 原 rotate(state, rotation)  // 已旋转 HORIZONTAL_FACING
attachment = rotation.rotate(旧 attachment)
写回新的 attachment
```

这对 `ATTACHMENT_SURFACE == DOWN` 的普通水平漏斗成立；对侧面/倾斜 Surface 不成立，因为 `HORIZONTAL_FACING` 存的是 Surface 局部坐标，而 Minecraft 的 `Rotation` 作用于世界坐标。正确的变换应是：先用旧附着面把旧局部方向 worldize，旋转世界方向，再用新附着面 localize 回新的局部方向。一个典型反例是侧面 Surface 的旧局部 `EAST`，旋转附着面后继续直接使用 Create 旋转得到的局部 `SOUTH`，重新按新 Surface 解码后可能变成竖直世界方向，而物理旋转后的漏斗口应仍是水平世界方向。

因此该 Mixin 当前存在真实的逻辑缺陷，不只是版本兼容问题。它会影响旋转、结构方块、Schematic、Contraption 变换后 Funnel 的朝向、形状、目标 Belt 面和捕获方向。建议将此项列为 P1：使用
`worldFacing = BeltSurface.worldizeCanonical(oldLocalFacing, oldAttachment.opposite())`，再计算 `rotatedWorldFacing = rotation.rotate(worldFacing)`，最后用新附着面反解 `newLocalFacing`，同时保留 Create 对普通状态的结果。

公开 API 替代性：若不修改 Create 的抽象块 API，Mixin 是补齐状态变换的最小方式；不能用单独的 accessor 替代，因为问题在行为变换而不是字段访问。若 Create 提供 Surface-aware rotation strategy，应迁移到该 API。

### 5.6 `BeltFunnelShapeMixin`

目标：`BeltFunnelBlock.getShape` 返回值，以及 ItemEntity 的 `getCollisionShape`。

必要性：新增属性只记录了附着面，还必须把 Create 的 Funnel VoxelShape 从局部坐标旋转到世界坐标，否则侧面 Surface 会出现逻辑方向正确但模型/碰撞仍朝向普通水平面的错位。当前实现只对 `tiltedOutwardNormal != null` 的状态修改返回值，普通 `DOWN` 状态直接保留 Create 原始结果。

这是本组中侵入性较低的几何 Mixin：

- `getShape` 在原始 Shape 已算好后追加变换，不复制 Create 的 Shape 选择逻辑；新增 Shape cache 以完整 `BlockState` 为键，避免每次重新合并 AABB。
- `getCollisionShape` 只复刻 Create 原实现的 ItemEntity、`PULLING/PUSHING` 条件，其他 CollisionContext 和 Shape 保留原逻辑。使用独立的 item-collision cache，避免把轮廓 Shape 和物品阻挡 Shape 混用。
- 影响主要限于 Surface Funnel 的几何查询，普通方块和非物品实体不会改变。

兼容风险仍有三项：`AllShapes.FUNNEL_COLLISION` 和 Create 的 `VoxelShaper` 是内部实现；VoxelShape 合并后的 AABB 近似可能改变边界碰撞；BlockState 键包含新属性后缓存会额外占用内存。没有等价的 Create 公共 Shape 变换回调，当前 Mixin 可以保留，等级 P2/P3。应测试六个附着方向、四种 Shape、ItemEntity/玩家/射线三类查询，以及方块状态热重载和大量漏斗世界的缓存规模。

### 5.7 `BeltFunnelBlockMixin`

该 Mixin 同时包含五个行为入口，必须分别看待。

#### 5.7.1 `entityInside`

它先调用父类逻辑，再调用 `BasinEntityProcessing.handleFunnelEntityInside`，为普通 Funnel 和 Belt Funnel 增加小史莱姆捕获。该捕获函数首先限制服务端、实体类型、大小、存活状态、AABB 和目标 BE，因此普通实体和普通物品的原版行为不被覆盖。由于 Create 的 `BeltFunnelBlock` 本身不是 `FunnelBlock` 子类，不能通过复用 `FunnelBlock.entityInside` 获得同样的自定义捕获；在没有专用实体进入事件时，这个追加式 Mixin 是合理的，侵入性中等，风险 P2。

需要确认父类调用在当前 Create 继承层级中确实等价于原方法的默认行为；不要把它误认为完整调用了 `FunnelBlock.entityInside`，两者不是继承关系。若将来 Create 给 Belt Funnel 增加自己的 `entityInside` 实现，必须检查该注入是否重复插入或漏掉新增逻辑。

#### 5.7.2 `updateShape` 返回值的父 Funnel 朝向修正

Create 原逻辑在 Belt Funnel 失去有效 Belt 时，会把它转换回父 Funnel，并把 `HORIZONTAL_FACING` 直接写入父 Funnel 的世界坐标 `FACING`。本模组在返回值尾部只对“确实已转换为 FunnelBlock”的结果进行 worldize，修正了局部坐标写入世界坐标的问题；非转换路径完全保留 Create 结果。这个注入点是必要且相对克制的，公开 API 不足，等级 P2。

风险在于 `BeltSurfaceResolver` 可能在 Belt BlockEntity 刚创建、链条尚未初始化或区块边界加载不完整时返回空。`isOnValidBelt` 也会在 Provider 存在但 Host 尚未准备好时返回 false，从而触发不可逆的父 Funnel 转换。需要验证放置顺序、邻居更新顺序、区块重新加载和 Belt 初始化失败后的重试；更稳妥的策略是区分“明确无效”和“Host 尚未就绪”，对后者保留状态并等待下一次更新。

#### 5.7.3 `getShapeForPosition`

对能由 `BeltSurfaceResolver` 找到的 Surface，Mixin 用 Surface 的 worldize 方向和真实 movement axis 计算 `RETRACTED`/`PULLING`/`PUSHING`；找不到 Surface 时返回，让 Create 原方法处理普通、Magma、Power Belt。这种条件式取消保留了原实现，逻辑来源也与 Create 的“方向轴比较”一致，必要性高、侵入性中等。

但放置时依赖 `resolveForPlacement` 扫描六个邻居，扫描结果不绑定点击面或候选 Funnel 的最终朝向；同一位置存在多个可用 Surface 时，返回顺序可能决定结果。应增加多 Surface、区块未加载、相邻两条 Belt 和玩家从不同面放置的测试。当前方法目标是 Create 静态方法，描述符含 Minecraft 类型却没有关闭 remap，符合映射要求；这一点应保持。

#### 5.7.4 `isOnValidBelt`

该注入扩展了 Create 只检查 `pos.below()` 的语义，使侧面 Surface 根据状态编码的附着方向查询对应 Provider/Host；对于 `DOWN` 且不是 Provider 的状态让 Create 原始检查继续执行，因此普通 Belt 的有效性、Magma 的 `DirectBeltInputBehaviour` 和 Power Belt 的行为仍由原路径决定。设计上这是最低侵入的扩展方式。

风险是 Provider BlockState 与 Host BlockEntity 被拆成两级判定，任一级短暂缺失都会返回 false；另一个兼容风险是第三方若复用 `BeltFunnelBlock` 但设置了自定义 `ATTACHMENT_SURFACE`，会被本模组视为 Surface 语义并可能拒绝其 Belt。由于该属性是本模组独占新增属性，第三方主动使用的概率低，但应在兼容说明中列出。

#### 5.7.5 `onWrenched`

只有 Surface 能被解析时才取消 Create 原方法并自行切换 Shape；普通、Magma、Power Belt 返回让其他 Mixin或 Create 处理。Surface 的 `EXTENDED` 只允许在 outward normal 为 `UP` 且 movement 为水平时出现，这与当前几何模型一致；Funnel Kiss 奖励和服务端/客户端返回语义也基本保留。

这里与 `MagmaBeltFunnelBlockMixin` 共同注入同一个 `BeltFunnelBlock.onWrenched`，默认优先级相同。当前两者通过“Surface resolver 非空”和“下方是 Magma Belt”互斥，正常自有方块不会同时命中；但没有显式优先级或冲突声明，第三方同时实现 Provider/自定义 Magma 判定、或未来目标方法改变后，两个取消回调可能互相覆盖结果。建议为两个 Mixin 设置明确优先级或合并成一个按 Belt 类型分派的入口，并补充双 Mixin 注入顺序测试。

综合结论：此 Mixin 的条件式扩展思路正确，普通 Create 逻辑大部分被保留；主要风险是 Surface Host 初始化时机、多个 Surface 的确定性和同目标 Mixin 的协调。必要性高，侵入性中高，整体 P1/P2。

### 5.8 `FunnelBlockMixin`

目标：`FunnelBlock.entityInside` 追加小史莱姆捕获，以及 `getStateForPlacement` 返回值转换为 Surface Belt Funnel。

`entityInside` 是纯追加：捕获函数本身限制服务端小史莱姆和正确的输入方向，普通 ItemEntity 的 Create 插入逻辑完全不被取消或替换。它会在每次普通 Funnel 的实体接触路径上增加一次筛选，属于必要的功能注入，性能影响低，风险 P2。

`getStateForPlacement` 在 Create 已算完普通 Funnel 状态后查询 Surface，并在局部朝向仍为水平时构造等价 Belt Funnel：它保留了原来的 `POWERED` 和 Funnel 类型，通过 `getEquivalentBeltFunnel` 使用 Create 自己的块映射，再设置本模组的附着面、局部朝向和 Shape。对没有 Surface 的放置完全返回原状态，因此原版放置逻辑完整。

该入口无法用普通注册 API 替代，因为 Create 的等价 Belt Funnel 决策封装在 FunnelBlock/FunnelItem 中，没有公开的“选择自定义附着面”回调。当前实现比复制整个 `FunnelBlock.getStateForPlacement` 更低侵入。不过它依赖 `getEquivalentBeltFunnel`、`ProperWaterloggedBlock.withWater`、`AbstractFunnelBlock.getFunnelFacing` 等 Create 内部实现；并且若 Surface 在放置瞬间尚未准备好，初次放置会成为普通 Funnel，当前被注释掉的自动 re-attach 路径不会补回，造成“必须拆掉重放”的玩法差异。建议把这一限制明确记录，并验证两条 Belt、两条 Surface、含水方块和客户端预测放置。综合等级：必要性高，侵入性中等，风险 P2。

### 5.9 `FunnelItemMixin`

目标：`FunnelItem.getPlacementState` 方法头部。

Create 的 `FunnelItem` 有自己的专用分支：它先从世界朝向写入 `BeltFunnelBlock.HORIZONTAL_FACING`，再检查普通下方 Belt。对于本模组的 Surface Funnel，这个字段必须是局部坐标，直接走 Create 分支会把世界方向误当成局部方向。因此在有 Surface 时提前转调 `FunnelBlock.getStateForPlacement`，让 `FunnelBlockMixin` 统一完成局部化，是必要的坐标系修正。

该 Mixin 只在 `resolveForPlacement` 非空时取消原逻辑；普通 Funnel、普通 Belt、Magma、Power Belt 继续使用 Create 的 `FunnelItem` 原实现。侵入性低至中等，公开 API 没有更合适的放置状态拦截点；玩家交互事件只能控制是否放置，不能安全替换 `getPlacementState` 返回值。风险主要是两个放置入口必须长期保持一致，以及 Surface Host 尚未初始化时 resolver 为空会回退原分支。需要验证 `FunnelItem` 的子类、Brass/Andesite 两种 Funnel、潜行放置、客户端预测和旧状态复制。综合等级：必要性高，兼容风险 P2。

### 5.10 `FunnelBlockEntityMixin`

这是本组侵入性最高的 Mixin，包含捕获接口、模式判断、库存目标替换、过滤器数量能力和自定义 Surface Belt 抽取。

#### 5.10.1 `SlimeCaptureFunnelAccess`

`createBiotech$tryCaptureSmallSlime` 是给 `BasinEntityProcessing` 使用的运行时桥接。它只在服务端、Level 存在、冷却已到期且 Basin 捕获成功时触发 Funnel flap，并使用 Create 的默认抽取冷却作为节流。没有 Create 公共的“Funnel 接触实体后处理”接口，因此 accessor/接口型 Mixin 是必要的最低侵入方案。它不修改普通 Funnel tick 或库存逻辑，风险 P2。

#### 5.10.2 `determineCurrentMode`

该注入针对 Surface Belt 的 `RETRACTED/EXTENDED` Funnel：把局部方向转换为世界方向，再和 Surface movementFacing 判断是向 Belt 推送还是从 Belt 抽取；对 `PULLING/PUSHING` 形状、普通 Belt、Magma 未解析状态放回 Create 原实现。这一判断与 Create 原方法的分支来源一致，普通行为基本保持。

问题是返回类型是 Create 的包私有内部枚举 `FunnelBlockEntity.Mode`，当前代码用类名字符串反射和 `Enum.valueOf` 构造结果。它会产生三层脆弱性：

- 内部枚举名称、包名或常量名变化就会在运行时抛 `IllegalStateException`；
- 该方法每次只在 Surface 分支第一次命中时可能触发类加载，问题不会在编译期暴露；
- 反射绕开了 Mixin 映射和类型检查，其他模组若也修改 Mode 或 `determineCurrentMode`，取消顺序不可预测。

这是当前必须使用内部状态机、但实现方式仍可优化的典型。优先级从高到低的替代方案是：推动 Create 提供公开的 Funnel mode/Surface provider 回调；使用 Mixin 对 `tick` 中的模式调用做受控包装并保留原模式对象；若 Mixin 框架允许稳定 shadow 内部枚举，则使用编译期类型而不是字符串反射。不能直接声称“可以用公开 API”替代，当前版本没有等价 API。综合风险 P1。

#### 5.10.3 `addBehaviours`

在 Create 已创建所有行为后，用新的 `InvManipulationBehaviour` 替换原 `invManipulation`，只改变目标 `BlockFace` 的计算：普通状态的 `tiltedOutwardNormal` 为空，返回值与 Create 原 `(facing.opposite)` 一致；倾斜 Surface 才转换为世界方向。因此未复制/取消 Create 的过滤器、版本追踪、DirectBeltInput 或奖励注册，逻辑完整度较好。

风险在于替换行为对象发生在 `addBehaviours` 尾部，未来 Create 若在此前把旧对象注册到其他行为回调、监听器或缓存中，字段替换不会同步那些引用。当前 Create 6.0.10 的直接引用有限，但这是内部行为组合的版本耦合。若 Create 提供 `InvManipulationBehaviour` 的目标解析器 setter，应该改为设置 resolver 而不是替换实例；当前无该公开 setter。等级 P2。

#### 5.10.4 `supportsAmountOnFilter`

对 Surface Belt 的非 `PUSHING` Shape 强制返回 true，允许 Create 的数量过滤器在侧面/倾斜 Belt Funnel 上工作；`PUSHING` 让 Create 原逻辑执行。它只改变自有 Surface 状态，普通 Funnel 和普通 Belt 不受影响，逻辑目的与 Create 的“只有可抽取/可推送 Belt 才支持数量过滤”同源。风险 P2，主要是 Surface 解析失败时 UI 和抽取行为短暂不一致。

#### 5.10.5 `activateExtractingBeltFunnel`

这是一个在方法头部取消原方法后重写完整逻辑的注入。当前重写保留了 Create 6.0.10 原方法的关键步骤：版本追踪等待、`DirectBeltInputBehaviour` 获取、侧面可插入检查、占用检查、过滤器数量/模式提取、模拟插入回调、等待库存版本、实际 flap、`onTransfer`、实际插入和冷却设置。它只把原来固定的 `worldPosition.below()`/`HORIZONTAL_FACING` 替换为 Surface 的 `beltPos()`/`outwardNormal()`，这符合“逻辑同源”的要求，完整覆盖度在当前基线下较高。

仍应注意：

- 该完整覆盖只对 Create 6.0.10 成立；Create 后续在原方法加入统计、事件、音效、同步或额外的失败处理时，本实现不会自动继承。
- 目标方法是 Create 私有方法，当前 `remap = false` 对无 Minecraft 描述符的 `()V` 目标可以成立，但应在版本升级时重新检查。
- `invManipulation` 和 `invVersionTracker` 是私有字段 Shadow，任何字段重命名/行为重构都会导致启动失败或错误抽取。

因此，当前无法用公开 API 完全替代，但这是“必须取消时尽可能逐行保持原逻辑”的合格案例。建议把 Create 原方法逐版本复制对照纳入 CI 或人工升级清单，风险 P1/P2。

#### 5.10.6 总体评价

`FunnelBlockEntityMixin` 的接口追加和目标坐标替换是必要且相对克制的；Mode 反射和完整方法重写是主要不稳定来源。若只能优化一项，优先去掉字符串反射并推动公开 Surface-aware Funnel mode API；若不能改 Create，则至少增加启动时的枚举常量断言和针对 Surface Funnel 的服务器 tick smoke test。

### 5.11 `MagmaBeltFunnelBlockMixin`

目标：再次注入 `BeltFunnelBlock.getShapeForPosition` 和 `onWrenched`，专门识别下方的 Magma Belt。

必要性：Magma Belt 不是 Create 的 `BeltBlock`，但其 BlockEntity 提供了 `DirectBeltInputBehaviour` 的 Belt Funnel 支持。Create 的静态 `getShapeForPosition` 只读取下方标准 Belt 的 `HORIZONTAL_FACING`，不会根据 Magma Belt 的运输能力计算 Shape；扳手的 `EXTENDED` 判断也只认识标准 Belt。若不改 Create 的源码或提供新的可扩展回调，这两处 Mixin 是必要的。

逻辑完整性：

- `getShapeForPosition` 在非 Magma 时立即返回，让 Surface Mixin或 Create 原实现继续；在 Magma 时保留了 Create 的“运输能力检查、方向轴比较、提取/推送对应的垂直 Shape”逻辑。
- `onWrenched` 在非 Magma 时返回；在 Magma 时保留客户端成功返回、四种 Shape 的切换、水平坡度限制、方块更新和 Funnel Kiss 奖励。
- 它没有在扳手路径额外检查 `canTransportObjects`，而 Shape 计算路径检查了该条件。结果是某些不可运输的 Magma 状态仍可能被扳手切换 Shape；Create 标准 Belt 的扳手也主要按坡度判断，但对自有 Magma 应明确是否接受这一差异。建议使扳手可扩展逻辑与 `canTransportObjects` 的判定一致。

兼容性与同目标竞争：它与 `BeltFunnelBlockMixin` 以同样的注入点修改同一个 Create 类，当前通过“Surface resolver”和“pos.below() 的 Magma 判定”在自有内容上互斥，但默认优先级相同，没有显式声明注入顺序。若第三方 Belt 同时满足两种识别条件，两个 cancellable 回调可能出现先后覆盖、取消后仍执行或结果依赖配置加载顺序的情况。建议合并到一个统一的 Belt Funnel 扩展分派器，或至少设置明确优先级并在回调中先检查 `CallbackInfo`/扩展类型；同时为普通 Belt、Magma、Slime Surface、混合邻居各测一次。

公开 API：Magma BlockEntity 已使用 Create 的 `DirectBeltInputBehaviour.allowingBeltFunnelsWhen`，这能解决“是否有效”的公共行为注册，但不能改变 Create 静态 Shape 推导和扳手状态切换；因此只能部分替代，不能移除本 Mixin。综合等级：必要性中高，侵入性中等，同目标兼容风险 P1/P2。

### 5.12 `AssemblyOperatorBlockItemMixin`

目标：`AssemblyOperatorBlockItem.operatesOn` 方法头部。

它对水平 Slime Belt 和水平 Magma Belt 返回 true；其他方块不设置返回值，继续 Create 原实现。因此 Create 原本对标准 Belt、Basin、Depot、Weighted Ejector 的全部判断都保留，没有复制或取消无关分支。

必要性：Create 的 `operatesOn` 当前硬编码 `AllBlocks.BELT`，没有基于 Belt 能力或注册表的公开扩展列表；仅让自有 Belt Entity 注册 `DirectBeltInputBehaviour` 也不能让装配操作器的放置逻辑识别该方块。Mixin 是当前最小补丁，侵入性低。

兼容性：方法是 Create 的 protected 行为，注入描述符包含映射类型但没有错误设置 `remap = false`。当前判断只检查水平坡度，与 Create 标准 Belt 的条件同源；Magma 的 `canTransportObjects` 对水平状态通常为真，Slime 的水平状态也可运输。若未来新增自有 Belt 类型，需要继续维护该硬编码分支。

可替代方案是 Create 提供 `AssemblyOperatorBlockItem` 的 `Predicate<BlockState>` 注册 API，或把判断改成 Belt 能力接口；在当前版本不可用。综合等级：必要性中等，侵入性低，风险 P3/P2。

### 5.13 本批次结论与整改顺序

1. `AbstractHorizontalFunnelBlockMixin` 的局部/世界坐标旋转错误应优先修正；它会让侧面 Surface 的 Schematic、结构旋转和 Contraption 变换产生可见的错误方向。
2. `FunnelBlockEntityMixin` 的字符串反射 Mode 和 `activateExtractingBeltFunnel` 的完整重写是最高版本耦合点；前者优先寻找 API/编译期桥接，后者建立 Create 版本差异对照。
3. `BasinBlockEntityMixin` 与 `BasinInventoryMixin` 形成跨类隐式协议，应减少堆栈扫描、固定模拟/执行一致性，并修正带 Minecraft 描述符目标上的 `remap = false`。
4. `BeltFunnelBlockMixin` 与 `MagmaBeltFunnelBlockMixin` 共同修改同一目标方法，建议统一分派或显式优先级，避免后续第三方兼容依赖加载顺序。
5. `BeltFunnelBlockStateMixin` 属于无法简单移除的结构性 Mixin，必须把旧存档、结构方块、Schematic、旋转、区块边界和 Host 尚未就绪列为回归测试。
6. `BeltFunnelShapeMixin`、`FunnelItemMixin`、`AssemblyOperatorBlockItemMixin` 的普通路径保留较完整，可在上述核心风险下降低后处理性能和缓存细节。

本批尚未修改模组源码，也未运行编译或客户端验证；本节结论基于当前源码和 `ref/1.21.1/Create` 的 6.0.10 基线。下一批进入 Belt Tunnel、BeltMovement/Contraption 以及 Deployer、Schematicannon 等会取消 Create 原始逻辑的 Mixin。

## 6. 第三批：Basin 输出、Belt Tunnel 与运输适配

这一组的共同目标是让自有 Slime Belt 进入 Create 已有的 Basin 输出和 Tunnel 协议，同时阻止已经被 Basin 捕获的小史莱姆再次被普通 Belt 当作可运输实体。总体上，直接包装 Create 的 `DirectBeltInputBehaviour` 调用比复制 Tunnel 状态机更稳定；所有把 Slime Belt 伪装成 `BeltBlockEntity` 的地方都需要重点关注类型假设、缓存失效和模拟/执行一致性。

### 6.1 `BasinBlockSlimeBeltOutputMixin`

目标：`BasinBlock.canOutputTo(BlockGetter, BlockPos, Direction)` 静态方法头部。

必要性：Create 原方法只把标准 `BeltBlockEntity` 视为“没有实体阻挡时可输出的 Belt”。Slime Belt 不是 `BeltBlock`/`BeltBlockEntity`，但 Basin 需要根据 Slime Belt 的 FRONT/BACK Track 和运动方向选择输出方向；仅给 Slime Belt 注册 `DirectBeltInputBehaviour` 不能覆盖原方法中标准 Belt 的特殊分支。因此在当前 Create 版本中，该 Mixin 有实际必要性。

逻辑同源性：

- 邻居是 Funnel 时，继续遵守“Funnel 口朝向 Basin 输出方向则不可用”的原规则；非目标方向则继续检查输出 Belt 的接收能力。
- 邻居有非空碰撞形状时继续拒绝。
- 邻居为空且下方是 Slime Belt 时，使用 `resolveIOTrack` 选择 FRONT，并沿用 Create 标准 Belt 的“停止时可选、运动方向反向时不可选”判定。
- 邻居是 Funnel 时，要求 Slime Belt 的 FRONT Track 和下方输出的 `DirectBeltInputBehaviour.canInsertFromSide`，保持 Create 原方法的直接插入语义。
- 非 Slime 情况不设置返回值，完整回到 Create 原实现。

风险：

- 这是全局 Basin 方向选择入口，任何 Slime Belt 连接都会影响所有 Basin 的 lazy tick、输出缓存清理和方块朝向。若 Slime Belt Controller 未初始化，Mixin 返回原逻辑；初始化时序变化可能导致 Basin 暂时选择其他方向，随后不会立即切回，需验证 `notifyChangeOfContents/updateSpoutput` 的重算时机。
- 代码按 `BlockEntity` 类型而不是同时确认 BlockState，因此状态替换或方块实体延迟移除期间可能短暂识别到过期 Slime Belt。
- `FunnelBlock.getFunnelFacing(neighbour)` 对普通 Funnel 是世界方向；若未来这里遇到带 `ATTACHMENT_SURFACE` 的 Surface Belt Funnel，它的 `HORIZONTAL_FACING` 是局部坐标，直接与 Basin 世界方向比较会产生错误。当前典型 Basin 输出位置通常是普通墙面 Funnel，但应明确排除或先 worldize Surface 状态。
- “速度为 0 时返回 true”复制了 Create 标准 Belt 的 `BeltBlockEntity` 分支，但 Slime Belt 自身 `canInsertFrom` 会拒绝速度为 0 的实际插入。这样可以让 Basin 选择停止的 Belt，但后续输出可能进入缓存或反复重试；这是设计目标还是浪费重试，需要通过停止/启动 Belt 的输出测试确认。

公开 API：Create 没有 `BasinBlock.canOutputTo` 的注册式输出方向策略。可以在自有 Basin 方块中实现，但会失去与 Create Basin 的兼容；当前不能用更低侵入方式完全替代。综合等级：必要性高，侵入性中等，核心输出风险 P1/P2。

### 6.2 `BeltTunnelBlockMixin`

目标：`BeltTunnelBlock.canSurvive`、`isValidPositionForPlacement`、`updateShape`、私有 `getTunnelState`、私有 `hasValidOutput`。

#### 6.2.1 生存和放置判定

Create 的 Tunnel 只接受下方标准 Belt 且要求水平坡度；本 Mixin 对水平 Slime Belt 直接返回 true，从而绕过原版的 `BeltBlock.CASING` 检查。Slime Belt 没有 Create 的 `CASING` 属性，所以这是必要的适配；判断限定为 Slime Belt 且水平，普通 Belt 和其他自定义方块仍走原逻辑。

这里的取消范围是整个 `canSurvive/isValidPositionForPlacement`，但自有分支没有其他需要保留的 Create 条件：标准实现的核心条件就是标准 Belt、水平坡度和（仅 `canSurvive`）casing。因此对自有 Slime Belt 的覆盖基本完整。风险在于没有检查 Slime Belt 链是否已初始化、是否可运输或是否存在有效 Controller，Tunnel 可以先放置在一个尚未完成链初始化的方块上；这是由 Slime Belt 的延迟初始化决定的，需要确认 Tunnel 在下一次状态更新时能正确恢复连接。

#### 6.2.2 `updateShape` 的 Capability 失效

在原 `updateShape` 前、仅当垂直邻居（通常是 Tunnel 下方 Belt）变化时调用 `SlimeBeltTunnelCapabilityInvalidator.invalidate`，然后让 Create 原方法继续。该注入不取消原逻辑，目标是清除 Create `BeltTunnelBlockEntity.cap` 对下方 Item Capability 的缓存，并调用 NeoForge 的能力失效。它比重写整个 `updateShape` 低侵入，但依赖“向下邻居变化必然意味着 Capability 可能变化”的时序假设。

应检查方块替换、区块卸载、Slime Belt Controller 重连、普通 Belt 与 Slime Belt 互换以及客户端更新。若 Tunnel BE 尚未创建，invalidator 只调用 `level.invalidateCapabilities`，后续 Capability 注册是否会重新查询必须实际验证。

#### 6.2.3 `getTunnelState` 与 `hasValidOutput`

对水平 Slime Belt，Mixin 复制 Create `getTunnelState` 的主要算法：按 Belt 轴设置 Tunnel 轴，检查左右输出，设置 `T_LEFT/T_RIGHT/CROSS`，直线时检查窗口。`canHaveWindow` 通过 Shadow 调用 Create 原方法，窗口判定继续使用 Create 的相邻 Tunnel/Extended Funnel 逻辑；非 Slime 直接回原方法。

`hasValidOutput` 对相邻 Slime Belt 使用自有 `isValidOutput`，标准 Belt 仍按 Create 的轴比较，其他输出仍调用 `DirectBeltInputBehaviour`。因此原逻辑没有被全局复制，只在自有 Belt 分支替换了“输出是否存在”的判定。

主要风险是自有分支复制了 Create 私有 `getTunnelState` 的结构状态机，未来 Create 增加新的 Shape、窗口条件或连接通知时不会自动继承；同时 `getTunnelState` 目标没有显式描述符，依赖方法名唯一和私有方法未重命名。当前不能用公开 API 完全替代，因为 Create 没有 Tunnel state provider/输出 resolver 注册点；可考虑推动 Create 暴露这两个策略接口。综合等级：必要性高，侵入性中高，风险 P1/P2。

### 6.3 `BeltTunnelBlockEntityCapabilityMixin`

目标：`BeltTunnelBlockEntity.cap` 字段，并实现 `SlimeBeltTunnelCapabilityInvalidator`。

必要性：Create Tunnel 的 Item Capability 是惰性缓存，原 `invalidateCapabilities()` 不会自动把 `cap` 清空；当下方标准 Belt/Slime Belt替换时，若不清理字段，Tunnel 会继续把物品写入旧 Belt handler。当前没有公开 setter 或缓存失效策略，窄 accessor/桥接是合理的最低侵入方式。

它不改 Create 的能力注册、插入和提取逻辑，仅提供一个清空缓存的接口，普通 Tunnel 的运行路径不增加额外分支。风险集中于 Create 私有/保护字段 `cap` 改名或改成不同缓存结构；`remap = false` 用于纯第三方字段和无映射 Minecraft 描述符可以成立，但升级时必须重新核对。公开 API 只有 `invalidateCapabilities`，不足以替代字段清理。综合等级：必要性高，侵入性低，兼容风险 P2。

### 6.4 `BeltTunnelInteractionHandlerMixin`

目标：`BeltTunnelInteractionHandler.flapTunnelsAndCheckIfStuck` 内两次调用：`DirectBeltInputBehaviour.canInsertFromSide` 和 `handleInsertion`。

这是本批中侵入性较低的行为适配：使用 MixinExtras `@WrapOperation`，仅在目标输出位置是水平 Slime Belt 时把检查和插入转给 `SlimeBeltTunnelInteractionHandler`，其他所有调用都 `original.call(...)`。它没有取消整个 Tunnel 传输方法，也没有复制 Create 的 Brass 分配、Andesite Junction 分流、flap、显示链接通知和返回值流程，逻辑保留度高。

必要性：Create 的 Tunnel handler 只认识 `DirectBeltInputBehaviour`，而 Slime Belt 的 FRONT Track 需要通过 `SlimeBeltBlockEntity` 的 Track planner 插入；在没有公共 Tunnel output provider 的情况下，包装具体调用是比重写 handler 更好的选择。

兼容性风险：

- `@Local BlockPos outpos` 和 `@Local Level world` 依赖目标方法当前字节码的局部变量捕获；Create 改变局部变量、循环结构或编译器产物后，Mixin 可能无法应用或捕获错误局部。
- `canInsertIntoFront` 和 `insertIntoFront` 内部有速度、Controller、Track、占用和模拟/执行判断，当前与两次原调用的顺序匹配；必须测试模拟调用不写入、实际调用只写入一次、插入失败时 Tunnel 仍保持阻塞。
- `getHorizontalSlimeBelt` 通过 BlockState 和 BE 双重判断，Host 尚未准备好时会回退到原 `DirectBeltInputBehaviour`，可能把 Slime Belt 当作不可用输出；需要配合 Capability/链初始化测试。

公开 API：如果 Create 提供 `TunnelOutputHandler` 或把 `DirectBeltInputBehaviour` 的插入策略扩展为可注册接口，可以移除该 Mixin；当前没有。综合等级：必要性高，侵入性低至中等，风险 P2。

### 6.5 `BrassTunnelBlockEntityMixin`

目标：`BrassTunnelBlockEntity.tick`、`addValidOutputsOf` 中的 `BeltHelper.getSegmentBE`，以及 `insertIntoTunnel` 方法头部；另有一个输出能力检查包装。

#### 6.5.1 BeltEntity 适配视图

Create Brass Tunnel 的 tick 和有效输出搜索把下方对象静态当作 `BeltBlockEntity`，读取 `getSpeed/getMovementFacing`。Slime Belt 不是该类型，所以代码使用 `SlimeBeltTunnelBeltView` 创建一个只读的 `BeltBlockEntity` 子类，把这两个查询委托给真实 Slime BE，再通过 `@WrapOperation` 在原调用返回空时提供视图。

这个方法避免了复制 Brass Tunnel 的整个分配状态机，且当前 Create 调用点只使用速度和方向，设计上比完整重写低风险。但“伪造一个已注册为标准 Belt 类型的 BlockEntity”仍然是高耦合适配：若 Create 在这些调用点读取 `getBlockState`、`getControllerBE`、`index`、`getLevel` 或其他 Belt 字段，视图的默认构造值会产生错误；当前必须以 6.0.10 原方法逐行约束这个假设。视图缓存还需要在委托 BE 被移除/替换时确认不会持有旧对象造成生命周期泄漏，虽然每次命中会 `setDelegate`。

#### 6.5.2 `insertIntoTunnel` 完整取消重写

当下方或侧面输出是水平 Slime Belt 时，Mixin 在方法头取消 Create 原方法，并实现了当前 6.0.10 原逻辑的对应分支：空栈、过滤器、输出位置、Slime FRONT 插入、普通 DirectBeltInput、沿 Belt 方向无实体阻挡时的 ItemEntity 弹出、flap 和返回的空/原栈语义均被保留。没有命中 Slime 条件时直接回原方法，因此普通 Brass Tunnel 完整保留。

但它是本批最危险的完整替换之一：

- `insertIntoTunnel` 是 protected Create 方法，任何新过滤、分配统计、事件、声音、同步或输出缓冲逻辑都不会自动进入自有分支。
- 返回 `null` 表示拒绝、返回 `ItemStack.EMPTY` 表示完全接受的协议是隐式的；Slime 插入 helper 必须始终保留该协议，否则 Brass Tunnel 会错误地重复投递或丢失物品。
- 模拟路径必须绝不创建 ItemEntity、修改 belt inventory 或 flap；当前代码对 Slime 输出和 Direct handler传入 `simulate`，沿 Belt 弹出分支也只在 `!simulate` 时 eject，符合原实现，但要以测试锁定。
- `eject` 的位置、速度和方向是 Slime 自有实现，不可能直接证明与 Create Belt 的轨迹完全一致；需验证高速、反向、端点、无固体挡板和虚拟世界。
- `addValidOutputsOf` 的 `canInsertFromSide` 包装只在 offset 是 Slime Belt 时改用 FRONT 规则，其他输出走原调用；与完整插入重写的判定必须保持一致，否则会把实际不能插入的输出列入 Brass Tunnel 目标集合。

公开 API：当前没有让 Brass Tunnel 接受另一种 BeltEntity/输出协议的注册点。可以推动 Create 把 `BeltHelper.getSegmentBE` 抽象为只读 `IBeltTransportInfo`，把 `insertIntoTunnel` 抽象为输出 handler；在当前版本不能移除 Mixin。综合等级：必要性高，侵入性高，核心物流风险 P1。

### 6.6 `BeltMovementHandlerMixin`

目标：`BeltMovementHandler.canBeTransported(Entity)` 方法头部。

对已经标记为“捕获到 Basin”的小史莱姆返回 false，阻止 Create 普通 Belt 把其纳入 `TransportedEntityInfo` 并移动。捕获状态解除时 helper 不再返回 true，实体恢复原行为。对其他实体不设置返回值，Create 的存活、潜行玩家、纸箱装备等原始判定完整保留。

这是必要且非常窄的保护：没有 Create 的实体运输过滤注册 API，而仅设置 `NoAI` 不能阻止 BeltMovementHandler 收集实体。注入不影响 Slime Belt 自己的 `SlimeBeltMovementHandler`，因为目标类不同；如果捕获史莱姆被错误地从 Basin 释放，普通 Belt 会在下一次检查恢复运输，这符合状态来源。

风险主要在捕获标签持久化和移除时机：如果实体在跨维度、区块卸载或 Basin 移除过程中仍带捕获标签，普通 Belt 会拒绝它但其他移动系统可能继续处理。应与 `releaseCapturedSmallSlime`、实体死亡、Basin 被破坏和重载测试结合。公开 API 不足，当前 Mixin 可保留，综合等级 P2。

### 6.7 本批次结论与整改顺序

1. `BrassTunnelBlockEntityMixin` 的 `insertIntoTunnel` 完整替换和 `SlimeBeltTunnelBeltView` 假对象是最高风险点；应建立 Create 6.0.10 原方法逐分支对照、模拟/执行双测和升级差异检查。
2. `BeltTunnelBlockMixin` 复制了私有 Tunnel 状态算法，需重点覆盖窗口、T/Cross 连接、邻居变更和 Slime 链未初始化状态。
3. `BasinBlockSlimeBeltOutputMixin` 影响所有 Basin 的输出朝向，需验证停止 Belt、方向反转、两条候选输出和 Surface Funnel 坐标。
4. `BeltTunnelInteractionHandlerMixin` 是推荐保留的低侵入模式，但要把局部变量捕获和模拟插入纳入升级 smoke test。
5. `BeltTunnelBlockEntityCapabilityMixin` 与 Tunnel `updateShape` Mixin 形成缓存失效协议，必须一起测试普通 Belt/Slime Belt互换和区块重载。
6. `BeltMovementHandlerMixin` 的原逻辑覆盖最完整，保留即可，重点验证捕获标签生命周期。

本批尚未修改模组源码，也未运行编译或客户端验证；结论仍以本地 Create 6.0.10 参考实现为基线。下一批处理 Contraption、Deployer、Schematicannon 和 LaunchedItem 的多方块/蓝图逻辑，这些路径会直接取消 Create 原方法或扩展其持久化协议。

## 7. 第四批：Contraption、蓝图炮、Deployer 与伤害上下文

### 7.1 `BlockBreakingMovementBehaviourMixin`

目标：`BlockBreakingMovementBehaviour.damageEntities(MovementContext, BlockPos, Level)` 的 HEAD 和 RETURN。

必要性：本模组的 Bio Packager 需要在 NeoForge `LivingDamageEvent` 中知道“这次 Create 伤害来自哪个 Contraption”，以便在致死伤害前把实体装入 Contraption 内的空盒子。Create 6.0.10 的这些 DamageSource 不一定携带可直接回溯的 `AbstractContraptionEntity`，而 `damageEntities` 又是行为类内部方法，没有公开的伤害来源回调。因此在当前版本，围绕该方法建立临时上下文是必要的；注入本身没有改 Create 的伤害、投掷或实体过滤逻辑。

当前实现有 P0 风险：

- HEAD 只要 `context.contraption.entity != null` 就 `pushDamageContext`；RETURN 再次读取 `context.contraption.entity`，只有非空才 pop。
- 原方法中的 `entity.hurt` 会触发其他模组的伤害事件。任何事件监听器或被调用代码抛出异常，都会跳过 RETURN 注入，ThreadLocal 栈中的 Contraption 留在当前服务器线程。
- 如果方法执行期间 Contraption 被移除、字段被清空或上下文对象被替换，HEAD/RETURN 的非空条件不对称，也会泄漏一个已 push 的栈帧。
- 下一次同线程、同类 Create 伤害可能被错误归因到旧 Contraption，进而捕获不相关的 Mob，属于跨请求和跨实体状态污染。

这不是普通“异常时可能少清理”的 P2，而是该功能的身份判定边界。应改成一个保证 `finally` 的包装：优先用 MixinExtras `@WrapMethod`/等价方法包装原 `damageEntities`，进入时 push，`try/finally` 中无条件 pop；或者在 Mixin 层建立带 token 的上下文，并确保异常路径和实体变更路径也 pop 对应 token。仅增加第二个 `@Inject` 到更多 RETURN 点不能覆盖 Java 异常。

兼容性与替代性：它只作用于 Create 的 `BlockBreakingMovementBehaviour`，普通非该类伤害不增加逻辑；但目标方法签名和伤害调用顺序属于内部实现。若 Create 将 Contraption 写入 DamageSource，或提供公开的 movement damage context API，应删除该 Mixin，改由事件直接读取来源。综合等级：必要性高，侵入性中等，当前实现 P0。

### 7.2 `ContraptionMixin`

目标：`Contraption.moveBlock` 中标准 Belt 判断，以及 `moveBelt` 方法头部。

`moveBlock` 的 `@WrapOperation` 把 Create 的 `AllBlocks.BELT.has(state)` 扩展为 `original.has(state) || CBBeltChain.isBiotechBelt(state)`。它只扩展判断结果，不取消 Create 其余移动必要性、粘连、底座、轴承、座椅、活塞和结构大小限制；这是一个相对窄的适配。风险在于使用 `BlockEntry.has` 的 `ordinal = 0` 锁定字节码调用顺序，而不是更明确的 slice/调用语义。Create 未来在 `moveBlock` 前增加另一个同 owner 的 `has` 调用，ordinal 改变后可能注入错误调用或启动失败。

`moveBelt` 在自有 Slime/Magma/Power Belt 上取消原方法，转调 `CBBeltChain.addConnectedSegments`。当前 Create 6.0.10 原方法只根据 `BeltBlock.nextSegmentPosition` 加入前后两个相邻段；自有 helper 对三种 Belt 分别调用其 `nextSegmentPosition`，在这一基线下覆盖是完整的，且保留了 `visited`/frontier 由外层处理的协议。普通标准 Belt 不取消，原逻辑完整。

必要性：本地 `ref/1.21.1/Create` 的 `BlockMovementChecks` 公共 API 可注册“是否需要移动/是否可移动/是否粘连”等判断，但没有公开的 Belt 链邻接遍历注册点。让自有 Belt 直接继承 Create `BeltBlock` 会重复或破坏其 kinetic/运输实现，当前 Mixin 是最小可行接入。

风险与整改：

- `moveBelt` 属于 Contraption 组装核心方法，未来 Create 若在 Belt 遍历中加入多轨、端点、Tunnel、缓存或事件处理，自有分支不会继承。
- `CBBeltChain` 以 `instanceof` 判断自有类；第三方扩展自有 Belt、代理 Block 或状态包装时可能不被识别。
- 若自有 Belt 的链状态损坏，helper 只按候选位置加入 frontier，后续 `moveBlock` 才报错；错误位置和原 Create 的 AssemblyException 语义需要测试。

公开 API 只能部分替代，不能完全移除；建议推动 Create 提供 `BeltChainResolver`，并去掉 `ordinal = 0`，使用明确的调用匹配。综合等级：必要性高，侵入性中高，结构移动风险 P1/P2。

### 7.3 `AbstractContraptionEntityBufferPadMixin`

目标：`AbstractContraptionEntity.tick()` 返回处。

该 Mixin 在 Create 完成 Contraption 初始化、存储 tick、Contraption tick、实体 tick 和乘客定位后，追加一次 `BufferPadCollisionHelper.tickStaticWorldCollision`。它不取消原方法，且 helper 在客户端、无 Contraption、受支持类型之外会返回；对没有碰撞条件的实体行为保持原逻辑。

必要性：`BufferPadMovementBehaviour` 只能处理“Buffer Pad 随 Contraption 移动、检测静态世界”的路径；本功能还要处理静态世界中的 Buffer Pad 对移动 Contraption 的推挤/阻挡，Create 没有公开的 Contraption tick 完成事件。注入到 `AbstractContraptionEntity.tick` 是当前可获得最终世界位置和上一帧位置的直接入口。

主要风险是性能和时序：

- `supportsBufferPadCollision` 只排除 Controlled/Gantry Contraption，并没有先判断 Contraption 是否包含 Buffer Pad；因此普通大型 Oriented/Carriage Contraption 也会每 tick 遍历全部结构块、构造碰撞样本并查询邻近世界方块。
- 注入在乘客定位之后，碰撞修正可能晚于 Create 本帧的乘客位置更新，下一 tick 才完全反映；同时若移动 Pad 行为在同 tick 已经修正一次，需确认不会对同一实体施加重复逃逸速度。
- helper 用持久化 NBT 保存逃逸法向量和列车轴向，必须在 Contraption 被删除、解散、跨维度和重新加载时清理；否则会残留一次性推力。

可以用 Create/NeoForge 的 Contraption tick 事件替代时应迁移；当前搜索的 6.0.10 参考没有等价公开事件。短期应为 Contraption 缓存“是否包含 Buffer Pad”的标志，避免对普通结构做全量扫描，并验证普通大型机械、列车、受控 Contraption 和带乘客实体的碰撞。综合等级：必要性中高，侵入性中等，性能风险 P1/P2。

### 7.4 `DeployerMovementBehaviourMixin`

目标：`DeployerMovementBehaviour.activateAsSchematicPrinter` 方法头部。

该 Mixin 只在过滤器是已部署 Schematic、目标位置可替换、Schematic 中的状态是 Slime Belt 时接管；其他 Schematic 方块和所有普通 Deployer 行为都回到 Create 原实现。因此普通放置、物品抓取、假玩家模式、轨道进度和 Deployer 停滞逻辑没有被覆盖。

自有分支的必要性：Create 原方法对标准 Belt 只处理“单个端点/整条标准 Belt”的规则，且 `BlockHelper.placeSchematicBlock` 没有 Slime Belt 链连接和 Pulley 标记的构造协议。若不接管，Schematic 中的 Slime Belt 中段会被逐块当作普通方块，链初始化和轴状态可能错误。当前没有让 Deployer MovementBehaviour 为某种 Block 注册多方块 printer 的公共回调，Mixin 是必要的。

逻辑完整性：

- 先检查 Schematic 边界、目标可替换、状态类型；把 MIDDLE/PULLEY 标记交给端点的一次性处理，避免重复建链。
- 从当前端点反向寻找 START，再正向读取到 END，限制 1000 段并拒绝断链/短链。
- 先收集整条链的 `ItemRequirement`，再模拟并执行 Contraption 存储抽取，符合 Create 先检查资源再放置的原则。
- 使用 `BlockSnapshot` 保存所有链位置；建轴、调用 `SlimeBeltConnectorItem.createBelts` 后验证每段状态；多方块事件取消或任何异常/失败时逆序恢复快照并退还资源。

需要指出的差异和风险：

- Create 原 printer 是逐个状态调用 `BlockHelper.placeSchematicBlock`，会准备 BlockEntity NBT、按单块事件处理并遵循 Create 的特殊方块规则；自有链路径改成一次批量建链，故不会复制普通方块的行为，但这是有意限制在 Slime Belt 分支的条件式替换。
- `EventHooks.onMultiBlockPlace` 在已经建链后调用，取消时再恢复；监听器若在事件中观察/修改了链位置，快照恢复可能覆盖其合法修改，需确认 NeoForge 多方块事件的预期语义。
- `canPlaceChain` 只检查目标位置 `canBeReplaced`，而 `SlimeBeltConnectorItem.createBelts` 还依赖端点轴、加载状态、连接距离和可替换中间位置。当前失败后有恢复，但未在放置前完整模拟所有连接条件，可能出现短暂破坏/恢复和事件副作用。
- 资源抽取按每个链状态的 `ItemRequirement` 合并，但实际连接器建链可能只需要端点物品和轴；必须确认 Schematic 生成的每个 Slime Belt 状态对应的 requirement 不会重复扣除，特别是 `PULLEY` 中段。
- 如果 Schematic 缺少 START/END 或链超过 1000，当前分支取消这次打印而不让 Create 原逻辑尝试单块放置；这是避免坏链破坏世界的合理选择，但应记录为明确失败。

公开 API：可通过自定义 MovementBehaviour 替换整个 Deployer 行为，但会扩大对 Deployer 的影响，不能算更低侵入；真正合适的是 Create 暴露 Schematic printer 的多方块 handler。当前 Mixin 可保留，但必须有“资源不足、事件取消、链断裂、连接失败、玩家/区块卸载”测试。综合等级：必要性高，侵入性高，事务/玩法风险 P1。

### 7.5 `SchematicannonBlockEntityMixin`

目标：`shouldIgnoreBlockState` 和 `launchBlockOrBelt`。

`shouldIgnoreBlockState` 对 Slime Belt 的 MIDDLE 返回 true，语义对应 Create 对标准 Belt 中段的过滤，普通状态完整回原实现，侵入性低。

`launchBlockOrBelt` 对 Slime Belt 端点取消 Create 原逻辑：非最终端点发射一个轴，最终端点读取 Slime Belt 的 `beltLength`，发射一个无 casing 的 `LaunchedItem.ForBelt`，并把链中的 Pulley segment 偏移写入扩展接口。其 `isLastEndpoint` 与 Create 6.0.10 `stripBeltIfNotLast` 的 DOWNWARD/UPWARD/水平方向判断同源；普通标准 Belt、非 Belt 方块完整回原实现。

必要性：Create 的 `launchBlockOrBelt` 只认识 `AllBlocks.BELT` 和 `BeltBlockEntity`，无法把 Slime Belt 作为一条链发射，也无法把中段 Pulley 信息放进原 `CasingType[]`。当前没有 Schematicannon 的自定义 Belt launcher 注册 API，因此 Mixin 必要。

风险：

- `launchBelt` 是 Shadow 的 protected 方法，当前调用后立刻取 `flyingBlocks` 最后一个元素并假定它就是刚创建的发射对象；如果其他 Mixin/未来 Create 改变追加顺序，Pulley 偏移会写到错误对象。建议记录发射前的 list size，按新增对象定位并校验类型。
- `collectPulleyOffsets` 按世界中的当前链状态遍历；若炮弹发射期间链已部分卸载/损坏，`currentState` 可能不再是 Slime Belt，继续调用 `nextSegmentPosition` 可能产生错误状态读取。应在每步先确认状态类型，并对 offset 范围做限制。
- 单段 Slime Belt直接取消且不发射，避免连接器把坏链清理掉；这是安全选择，但改变了 Create 对单段 Belt 的“发射方块/轴”表现，需要在 UI 状态和缺失物品清单上验证。

完整性在当前基线下较好：Shape 判定、轴方向、末端条件、无 casing 的链长度和原始非目标分支都保留；未来 Create 若给 `launchBelt` 增加燃料、音效、统计或额外 NBT，自有分支不会自动继承。综合等级：必要性高，侵入性中高，风险 P1/P2。

### 7.6 `LaunchedItemForBeltMixin`

目标：`LaunchedItem.ForBelt` 的自有数据、`serializeNBT`、`readNBT` 和 `place`。

它用 `@Unique int[] createBiotech$pulleyOffsets` 扩展原发射对象的持久化协议；只有状态是 Slime Belt 且偏移数组非空时写入 `CreateBiotechPulleyOffsets`，普通标准 Belt 的原 NBT 完整保留。读取时在原方法尾部读取该键，缺失键保持兼容。

`place` 对 Slime Belt 完整替换原 `ForBelt.place`：按 START/END 反向规则算链方向，先放端点轴和 Pulley 轴，再调用 Slime Belt connector 创建整条链；长度小于 2 或无法算出下一段时取消，避免原 `BeltConnectorItem` 被错误用于自有 Belt。普通 `ForBelt` 返回原实现。

必要性：原 `LaunchedItem.ForBelt` 构造器/`place` 使用标准 `BeltConnectorItem`，且原 NBT 只有 casings 数组，没有自有 Pulley 位置字段。Mixin 是在不复制整个 Schematicannon 队列和发射计时器的情况下扩展该数据的最小方式；没有公开的 `LaunchedItem` subtype 注册点。

兼容性和数据风险：

- 新 NBT 数组没有校验数量、负值、重复值或是否小于 `length`；损坏/恶意炮弹 NBT 可能在任意相对位置写入 Shaft。`place` 前应过滤 `0 <= offset < length`，并限制数组长度。
- `readNBT` 在 `@Tail` 读取所有含该键的 ForBelt，即使 state 不是 Slime；当前 place 分支会忽略，但会保留无意义数据。可以在读取时按 state/长度筛选。
- 自有 `place` 不处理原 `casings`，当前 Schematicannon 为 Slime 传入全 NONE，符合模组设计；未来若支持带 casing 的 Slime Belt，需要扩展协议而不能默认为丢弃。
- 使用 `state.is(...)`、`length`、`target` 的 Shadow 依赖 Create 的字段可见性和初始化顺序；当前字段为 public，但这不是稳定 API。

公开 API：没有等价的发射对象扩展注册机制。该 Mixin 可以保留，但 NBT 校验和 place 前断言应优先补上。综合等级：必要性高，侵入性中高，存档/世界修改风险 P1/P2。

### 7.7 `BlockEntityConfigurationPacketMixin`

目标：Create `BlockEntityConfigurationPacket.handle(ServerPlayer)` 中对 `ServerPlayer.level()` 和 `canInteractWithBlock` 的两次调用包装。

该 Mixin 为无线 Stock Keeper 菜单的四类 Create Stock Ticker 配置包提供跨维度目标路由：当当前菜单是 `WirelessStockKeeperRequestMenu`、内容持有者存在且目标位置与包位置一致时，把基础 `world` 切换为内容持有者所在 Level，并在目标菜单仍有效时绕过玩家当前维度的普通距离检查。普通 Create 配置包、普通 Stock Keeper 菜单和无效目标都调用 `original`。

必要性：Create 的基础配置包固定使用 `player.level()`，没有目标 Level provider；单纯修改菜单 `stillValid` 不能改变 `handle` 后续 `getBlockEntity` 使用的世界。Mixin 只包装两个具体调用，比重写所有 Create 网络包处理低侵入。

安全与兼容性：

- 目标校验同时检查包位置等于 `contentHolder.getBlockPos()`、内容持有者未移除和 `menu.stillValid(player)`；这比仅按包类型切换 Level 安全。仍应确认菜单在玩家主动关闭、内容持有者卸载、目标区块未加载和维度销毁时的竞态。
- `LogisticalStockRequestPacket` 等 `applySettings` 仍接收原玩家对象；其中部分 Create 代码会向玩家维度发送声音/网络效果，而目标 BlockEntity 位于另一维度。跨维度“逻辑请求成功、反馈效果在玩家维度”的行为需确认是否可接受。
- `@WrapOperation` 只包装 `handle` 直接调用的 `ServerPlayer.level()`；不会改变包子类 `applySettings` 内部的 `player.level()`，这避免了更广泛的世界替换，但也意味着跨维度反馈不完全统一。
- `createBiotech$isWirelessTerminalPacket` 通过 `instanceof` 四个 Create 包类型筛选，未来新增 Stock Keeper 配置包不会自动支持；反过来，若 Create 包类层级调整，识别范围可能变化。

公开 API：目前没有 Create 网络配置包的 Level resolver 或远程菜单授权 API。应在 Create 提供该 API 后迁移；当前 Mixin 的包装范围是合理的，但应补充权限、关服/区块卸载、跨维度目标和重复包测试。综合等级：必要性高，侵入性中等，网络安全/兼容风险 P1。

### 7.8 本批次结论与整改顺序

1. 立即修正 `BlockBreakingMovementBehaviourMixin` 的 ThreadLocal 清理，必须保证异常路径、嵌套伤害和 Contraption 失效路径不会泄漏。
2. 为 `AbstractContraptionEntityBufferPadMixin` 增加“结构是否包含 Buffer Pad”的快速判定，避免普通大型 Contraption 每 tick 扫描全部结构。
3. 对 `DeployerMovementBehaviourMixin` 建立批量建链的资源事务、事件取消、失败恢复和区块卸载测试；确认每个 Slime Belt 状态的 requirement 不会重复扣除。
4. 对 `SchematicannonBlockEntityMixin`/`LaunchedItemForBeltMixin` 加强链长度、Pulley offset、损坏 NBT 和发射列表新增对象校验。
5. `ContraptionMixin` 的公共 API 替代只能等待 Create 暴露 Belt chain resolver；当前应移除脆弱的调用 ordinal 或至少检查编译期目标数量。
6. `BlockEntityConfigurationPacketMixin` 的目标校验方向正确，但跨维度副作用和菜单生命周期需要网络集成测试。

本批尚未修改模组源码，也未运行编译或客户端验证；报告结论以当前源码和 Create 6.0.10 参考为准。下一批审阅 Nether Portal/Fluid Tank、Slime Mimic 及实体/交易状态 Mixin。

## 8. 第三批：Fluid Tank、传送门与 Slime Mimic

本批覆盖 9 个 Mixin/Accessor：经验流体旧存档迁移、下界传送门流体化、LivingEntity 上的拟态状态、Create Haunting 处理以及村民交易替换。它们分成两类：Fluid Tank/传送门是存档或世界级基础设施扩展，风险来自目标范围大；Slime Mimic 是条件分支，风险相对集中，但实体数据同步、Haunting 的原逻辑覆盖和 MerchantOffer 复制必须严格核查。

### 8.1 `FluidTankBlockEntityMixin`

目标：Create `FluidTankBlockEntity.read(CompoundTag, HolderLookup.Provider, boolean)` 的 HEAD 和 TAIL；Shadow `tankInventory` 与 `isController()`。

必要性：模组把旧版经验罐的 `StoredExperience`/`Width` 数据迁移到 Create 6.0.10 的 `TankContent`/`Size`/`Height`/`Window` 格式，并且 `CBRemapHelper` 已把旧 `create_biotech:experience_tank` 的方块和方块实体类型别名到 Create Fluid Tank。别名只解决“用哪个 BlockEntity 类读取”，不能在 Create 的 `read` 之后插入旧字段转换；当前 Create 没有面向单个 BlockEntity 类型的公开 NBT load 回调，因此在 `read` 两端注入是必要的兼容接入点。

实现先保存旧 `StoredExperience` 到 Mixin 字段，再由 `LegacyExperienceCompat.migrateTankNbt` 原地补齐尺寸和 `TankContent`，让 Create 原方法继续负责 controller、容量、流体读取、Boiler、客户端同步和容量裁剪；服务端 TAIL 仅在旧值大于零、当前实例是 controller 且原 TankContent 为空时再填充一次。普通 Create Fluid Tank 没有这些旧字段时，HEAD/TAIL 都是空操作，原逻辑完整保留。这种“改 NBT、继续委托原读逻辑”的结构明显好于复制整个 Create `read`。

兼容性与边界：

- `clientPacket` 路径不会再次执行服务端填充，但 HEAD 已把转换后的 `TankContent` 交给 Create 原读逻辑，客户端可以获得同一内容；服务端/客户端的 CEI 经验流体选择必须保持一致，否则会出现流体 ID 不同的同步问题。
- `isController()` 只在原方法完成 controller 解析后于 TAIL 使用，时序正确；不过 Mixin 字段是实例可变状态，若未来 Create 在 `read` 内部重入另一个同一实例的 `read`，旧值会被覆盖。当前实现没有这种基线路径，但可用局部上下文或只根据迁移后的 `TankContent` 判定来降低隐式状态依赖。
- `LegacyExperienceCompat` 对旧 `StoredExperience` 采用 1:1 转换，并把旧值删除；如果旧值为负数、极大值或同时存在合法新 `TankContent`，迁移结果应由测试固定。当前服务端 TAIL 在新内容非空时不会覆盖它，但仍会删除旧字段，属于有意的数据优先级选择。
- `tankInventory.fill` 没有检查实际填充量。正常情况下 Create 原方法已经按 `Size * Height` 设置容量，且旧数据应不超过原罐容量；若损坏存档的旧经验超过新容量，代码会静默丢弃超出部分。建议在迁移前限制并记录，或用返回值/日志明确报告数据损失。

公开 API 替代：NeoForge 的数据附件不适用于替换第三方 BlockEntity 的既有 NBT 结构；Chunk load 事件也太晚且会覆盖所有方块实体，不能保证 Create 读逻辑前完成迁移。DataFixer 可以做版本级迁移，但需要可靠的旧数据版本和 BlockEntity schema，并不能自然覆盖当前别名路径。当前 Mixin 可保留，优先补旧格式、CEI 已安装/未安装、非 controller、客户端包、超容量和重复加载测试。综合等级：必要性高，侵入性中等，存档风险 P1；`@Shadow(remap = false)` 在这两个纯 Create/NeoForge 成员上不构成当前描述符映射问题，读方法本身保持默认 remap。

### 8.2 `NetherPortalBlockMixin`

目标：让原版 `NetherPortalBlock` 实现 `EntityBlock`，实现 `newBlockEntity`；在 `getExitPortal` 调用 `PortalForcer.findClosestPortalPosition` 后捕获返回的 `Optional<BlockPos>`。

必要性：每一个原版下界传送门方块需要拥有 `NetherPortalFluidBlockEntity`，才能按方块提供可抽取的传送流体、保存剩余容量，并在耗尽时破坏该传送门方块。仅注册 `BlockEntityType` 或 Capability 不能让一个本来不是 `EntityBlock` 的原版 Block 创建 BlockEntity；替换 `Blocks.NETHER_PORTAL` 的注册对象会破坏原版方块身份和大量兼容性。因此在目标类上增加 `EntityBlock` 是当前功能的直接且基本不可避免的 Mixin。

新增 BlockEntity 路径没有取消原版 `randomTick`、`updateShape`、实体进入、Portal 接口或方块状态逻辑；`newBlockEntity` 只为目标状态返回自有类型，其他状态返回 null。传送路径只在 PortalForcer 找到的坐标确实是 `CBFluids.TELEPORTATION` 时替换返回值，标准 Nether Portal 继续使用 Create/原版矩形和传送票据逻辑。

侵入性与原版影响较大：原版每个 Nether Portal 方块现在都是一个持久化 BlockEntity，即使玩家从未使用传送流体，也会增加区块 BlockEntity 数量、NBT 读写和 chunk unload/load 工作；Portal 方块通常成片存在，不能把它评估成单方块开销。每个 BlockEntity `onLoad` 还会遍历六个邻居并刷新 Create fluid network。普通传送门的玩法在容量未耗尽前保持，但世界规模、性能和第三方按“下界传送门没有 BlockEntity”假设编写的代码会改变。

`@At(INVOKE_ASSIGN)` 加 `@Local Optional<BlockPos>` 比复制整个 `getExitPortal` 低侵入，但仍依赖 Create/原版当前唯一的 Optional 局部赋值和目标调用描述符。若未来原版在查询前后增加另一个同类型局部，局部捕获或启动可能失败。应优先改成更明确的 slice/局部捕获约束，并为传送门形成、破坏、重载、区块边界和多方块流体候选建立测试。

对自有传送流体的矩形使用 `new FoundRectangle(fluidPos, 1, 1)`，依靠原版 `getDimensionTransitionFromExit` 的“无 `HORIZONTAL_AXIS` 时默认 X”分支完成目的地定位。这保留了原版实体相对入口的方向、速度和尺寸计算，但 1x1 流体单元不是原版多方块 Portal rectangle；大型实体、边缘进入、实体位置偏移和不同入口轴向必须实际验证。`postTransition` 只放置以流体位置为中心的 Portal ticket，不应误认为具备原版 Nether Portal 的完整矩形语义。

公开 API 替代性：没有公开的“为现有 Block 动态提供 BlockEntity 工厂”的 NeoForge API；Capability 注册已经是公共 API，但依赖本 Mixin 提供实例。该 Mixin 暂时无法移除，建议把流体抽取开关、BlockEntity 创建、传送逻辑拆成独立可配置策略，并明确关闭抽取是否仍允许流体传送。综合等级：必要性高，类级侵入高，普通世界性能/兼容风险 P1。

### 8.3 `PortalForcerMixin`

目标：`PortalForcer.findClosestPortalPosition` 的 RETURN；Shadow `PortalForcer.level`。

该 Mixin 在原版已经完成 `ensureLoadedAndValid`、标准 Nether Portal POI 查询、世界边界过滤和距离/Y 轴排序后，追加一次传送流体 POI 查询，再以同一比较器与原始候选竞争。它没有取消原方法，也没有复制 PortalForcer 的区块加载流程；从原版逻辑同源性看，属于“扩展候选集”的窄实现。`level` 是纯 PortalForcer 成员，当前 Shadow 不需要关闭 remap。

必要性：`PortalForcer` 没有公开的 portal candidate provider 或搜索结果合并回调；如果只在 `NetherPortalBlock.getExitPortal` 外部另行查询，会复制维度缩放、边界和“最近候选”的决策，还无法保证与原版传送创建分支一致。当前 Mixin 是把自定义流体纳入原版最近传送门选择的最小入口。

主要兼容和玩法风险：

- 这是全局影响所有原版下界传送的 Mixin。只要 POI 类型存在且该位置仍是传送流体，任何实体/玩家的原版 Nether Portal 搜索都可能被更近的流体候选抢占；它不区分“正在使用自有流体”的入口。
- 查询按 `isNether ? 16 : 128` 使用与原版相同半径，并过滤 WorldBorder 和流体类型，这是逻辑同源的优点。但它没有独立的 config guard；`enablePortalExtraction = false` 只关闭 Capability，并不会关闭流体作为传送目标的候选。若配置文案被理解为关闭整套传送流体功能，这就是配置语义缺口。
- POI 类型覆盖 `TELEPORTATION_BLOCK` 的所有可能状态，且查询只再判断流体类型，不限制 source level。流动传送流体的每个 POI 都可能成为目的地，流体扩散范围越大，传送目标越不可预测；这可能是设计目标，也可能让一小滩流体意外截获普通下界传送。
- `getInSquare` 的结果依赖 POI 数据随流体方块更新的时序。流体流动、方块被破坏、区块刚加载时，POI 与实际 FluidState 不一致会被过滤掉或暂时存在；需要测试流体更新和区块边界。

公开 API 替代性：原版/NeoForge 没有 portal search 合并事件。若 Create Biotech 能把传送流体限定为显式的 `Portal` 入口，并在目标世界创建专用传送门方块，则可以不改 PortalForcer，但会失去“流体位置直接作为目的地”的玩法。保留 Mixin 时建议把候选查询抽成可配置 resolver，至少增加 `enableTeleportationFluidPortals`（或明确沿用 extraction 开关），限制候选为 source/指定形态，并测试“标准门更近、流体更近、同距、跨边界、流体流动”五类排序。综合等级：必要性中高，侵入性高，原版传送影响 P1。

### 8.4 `LivingEntitySlimeMimicMixin`

目标：`LivingEntity.defineSynchedData`、`addAdditionalSaveData`、`readAdditionalSaveData`；为所有 LivingEntity 增加一个 Boolean `EntityDataAccessor` 并实现 `SlimeMimicAccess`。

必要性：拟态对象可以是任意 LivingEntity，不能仅通过新增实体类型或某一个具体 Mob 子类表达；状态同时需要服务端存档、客户端渲染/声音识别和运行时切换。当前 `SynchedEntityData` 是 Minecraft 对实体网络状态的标准机制，Mixin 只添加一个字段和三个序列化/初始化钩子，没有替换 LivingEntity 的 tick、AI、伤害或生命周期，因此在“任意 LivingEntity + 客户端可见”这个需求下属于合理的公共底层接入。

但其范围是全体 LivingEntity：每个实体都多一个同步数据项，并且所有实体的保存/读取都会经过两个条件很轻的注入。相比自定义实体，兼容面更广；相比 NeoForge AttachmentType，它的全局类侵入和协议依赖更大。

可替代方案：NeoForge 21.1.219 已提供注册式 `AttachmentType`，可用 Codec/serializer 持久化，并可用 `sync(StreamCodec)` 同步给客户端。将状态迁移到一个 `AttachmentType<Boolean>` 或小型状态对象，可以移除 `LivingEntity` 的 `defineSynchedData`、读写三个注入，保留 `SlimeMimicAccess` 的内部 facade；这是本 Mixin 最明确的低侵入优化方向。迁移时必须处理旧 `CreateBiotechSlimeMimic` NBT、实体首次同步、死亡复制策略和客户端 render cache，不能直接切换而丢失存档。

当前逻辑的两个边界：

- `readAdditionalSaveData` 直接调用 accessor setter，而不是 `SlimeMimicHandler.setSlimeMimic`。因此加载 NBT 时不会执行村民交易恢复，也不会重新按当前 allowlist 验证；这对保存后的配置变更可能是“保留既有拟态”的合理选择，但会让读取路径与运行时切换路径语义不同，必须记录为协议决定。
- 该字段在实体构造期间由所有 LivingEntity 的 `defineSynchedData` 追加。任何同样使用错误目标类注册 DataAccessor 的第三方 Mixin 都可能造成 ID/初始化冲突；这是全局基础类注入的通用风险。

普通实体没有该 NBT 键时设为 false，且所有条件分支先检测该标志，原版行为保持。综合等级：必要性高，侵入性中高，当前逻辑风险 P2；建议优先评估 AttachmentType 迁移，若暂时保留则补服务器/客户端实体同步、重载、死亡/转换和旧 NBT 迁移测试。

### 8.5 `LivingEntitySlimeMimicHurtSoundMixin`

目标：`LivingEntity.getHurtSound(DamageSource)` 与 `getDeathSound()` 的 HEAD。

对非拟态实体完全回到原方法；对拟态实体只替换受伤和死亡声音，并按尺寸/是否为 Slime 选择大小史莱姆声音，不改变伤害计算、死亡掉落、状态效果或事件。它是条件短路而非所有 LivingEntity 声音逻辑的重写，侵入性低至中等，原版影响面清晰。

必要性：受伤/死亡声音方法是 protected，NeoForge 的通用 `PlayLevelSoundEvent.AtEntity` 可以改最终播放的声音，但事件不携带“这是 hurt 还是 death”的可靠语义；仅凭原始 SoundEvent 不能覆盖任意模组实体的自定义声音。因此在需要精确替换这两个语义点时，当前 Mixin 比事件层更完整。若未来只要求所有声音都改成史莱姆，事件层可以作为低侵入替代，但会扩大误替换范围。

兼容性风险主要是方法签名和其他模组对 `getHurtSound`/`getDeathSound` 的优先注入顺序。该 Mixin 使用 HEAD cancellable，会阻止后续 Mixin 以及原实体自定义声音；这对“拟态采用史莱姆声音”是预期，但与给拟态增加盔甲/特殊伤害声音的兼容性较差。`isLargeSlimeVoice` 采用 `> 0.75f` 的包围盒阈值，不是 vanilla Slime 的尺寸 API，属于模组自定义规则，应补边界值 0.75、幼体、超大实体和自定义实体声音测试。综合等级：必要性中等、侵入性中等、原版影响 P2；没有发现非拟态路径缺陷。

### 8.6 `HauntingTypeSlimeMimicMixin`

目标：Create `AllFanProcessingTypes.HauntingType.affectEntity(Entity, Level)` 的 HEAD cancellable。

必要性：Create 6.0.10 的 `HauntingType` 是注册到 `CreateBuiltInRegistries.FAN_PROCESSING_TYPE` 的单例；其 `affectEntity` 直接对实体施加失明/缓慢，并负责 Horse -> Skeleton Horse 的进度转换。当前没有 entity-specific 的 Haunting callback。要让史莱姆拟态在 Soul Fire Fan 中按再生效果逐渐恢复原实体，必须在该实体进入 Haunting 的处理点做分流，故当前 Mixin 有实际功能必要性。

原逻辑覆盖结论必须保守：对非 LivingEntity 或非拟态实体，原方法完整执行；对拟态实体，代码调用 `advanceHaunting` 后无条件取消原方法。这样确实阻止了 Create 对拟态 Horse 的 Skeleton Horse 转换，但也同时跳过：

- 服务端每 tick 的 `BLINDNESS` 和 `MOVEMENT_SLOWDOWN`；
- 客户端 Horse 的 Soul Fire Flame/Large Smoke 粒子；
- Create Haunting 原有的实体分支和其未来可能增加的统计、音效、事件。

因此自有分支不是“完整复制原逻辑后替换转换目标”，而是完整替换了该实体的 Haunting side effects。若设计目标是“拟态不会被默认 Haunting 转化，但仍受到 Haunting 的正常视觉/减速效果”，当前实现不满足逻辑同源要求；应在自有 handler 中保留原版状态效果和必要客户端粒子，或者把注入收窄到 Horse 转换/进度字段的调用。若设计目标明确是“拟态只处理再生恢复，不受默认 Haunting 状态影响”，则应在代码注释和配置文档中明确这是玩法改变，并补充回归测试。

公开 API 替代：这里比当前报告早期判断更有优化空间。Create 提供公开的 `FanProcessingType` 接口和 `CreateBuiltInRegistries.FAN_PROCESSING_TYPE` 注册表。可以注册一个与 `HAUNTING` 相同 `isValidAt`、更高优先级的 wrapper，除 `affectEntity` 对拟态分流外，把 `canProcess`、`process`、粒子和气流效果全部委托给原 `AllFanProcessingTypes.HAUNTING`。这样不改 Create 类字节码，但会改变选中的 processing type ID、物品处理 NBT 和优先级排序，必须保证 wrapper 对非实体路径完全委托，并在 registry bake 前注册。若不能接受 registry identity 变化，当前 Mixin仍是兼容性更可控的方案。综合等级：必要性中高，当前取消侵入高，玩法风险 P1/P2；优先级是“保留原 Haunting side effects”或改为公开 wrapper。

### 8.7 `AbstractVillagerSlimeMimicTradesMixin`

目标：`AbstractVillager.readAdditionalSaveData` 的 TAIL，以及 `getOffers()` 的 RETURN cancellable；通过 `AbstractVillagerAccessor` 读写 `offers`。

必要性：村民存档读取时 vanilla 直接把 Offers 解码后写入 protected field，不经过 `Villager.setOffers`；仅注入 Villager 的 setter 会漏掉已保存交易。`getOffers` 又是所有交易 UI/服务器请求的公共读取入口，作为兜底可以处理初始生成、重载和其他子类。当前两处都是尾部追加/返回值替换，普通非拟态村民会快速返回，不复制原版交易生成逻辑。

原逻辑与风险：`SlimeMimicVillagerTrades.rewriteSellItems` 保存每个 offer 的原始 result NBT，再创建新的 `MerchantOffer` 仅替换 result 为 Slime Ball；成本、uses、maxUses、xp、priceMultiplier、demand 和 specialPriceDiff 被复制，交易列表顺序保留。问题是 `MerchantOffer` 还有 `rewardExp` 字段，而当前公开构造器调用没有传入该值，默认值可能与原 offer 不同；因此被改写/恢复的交易不保证保留原版“是否奖励经验”的语义。它应被记录为逻辑不完整，建议通过 `MerchantOffer` accessor、以原 offer copy 为基础的可变 result API（如未来开放）或其他结构化复制方案解决。

此外，`getOffers` 注入在 vanilla 方法 RETURN 后重新取字段并强制返回。服务端 vanilla 在 offers 为空时会先调用 `updateTrades`，所以该顺序可以覆盖首次生成；客户端 vanilla 会在 RETURN 前抛出“Cannot load Villager offers”，不会被该注入绕过。对普通村民没有控制流改变。配置 `rewriteVillagerTrades` 当前不在 helper 中检查，导致只要实体是 Slime Mimic，加载/访问交易时仍会改写，即使配置为 false；`SlimeMimicHandler.setSlimeMimic` 中的配置判断不足以修复后续 `getOffers`/`readAdditionalSaveData` 路径。这是明确的 P1 配置行为缺陷。

低侵入替代：若只支持 `Villager`，可以在公开 `setOffers`/交易刷新路径集中处理，并用 NeoForge 交易事件覆盖生成阶段；但这仍漏掉 AbstractVillager 的存档直接赋值，且不能安全恢复结果。更稳妥的是保留当前窄 Mixin，统一在 helper 入口检查配置、增加防重入/offer 数量一致性校验，并优先补 rewardExp 保留。综合等级：必要性高，侵入性中等，存档/交易兼容风险 P1。

### 8.8 `VillagerSlimeMimicTradesMixin`

目标：`Villager.setVillagerData` HEAD、`setOffers` TAIL、`updateTrades` TAIL。

`setVillagerData` 的职业变化判断与 vanilla 同源：原版只有职业变化时才把 offers 置空，因此 Mixin 在 HEAD 清除旧的原始 result 快照，避免新职业沿用旧交易映射。它没有取消或修改 vanilla 的 VillagerData 写入，必要性中等、侵入性低。

`setOffers` 和 `updateTrades` 的尾部注入用于处理两条 vanilla 不经过 AbstractVillager load 兜底的运行时路径。`updateTrades` 在 1.21.1 中向现有 Offers 追加 listing，而不是每次都替换整个列表；helper 的“按 index 追加原始结果”在这一基线下可以保持旧交易与新追加交易的映射。普通 Villager 的原逻辑全部执行。

需要关注的边界：

- 外部模组调用 `setOffers` 传入一份与旧列表不同长度/不同结果的集合时，旧的 `CreateBiotechOriginalOfferResults` 不会截断或按 offer identity 重建；同索引的新结果可能被误认为旧结果，导致错误替换，旧快照也可能在恢复时覆盖外部合法修改。
- `rewriteSellItems` 通过“当前 result 是否等于快照”判断是否仍是原始报价。这对 vanilla 的追加模型有效，但对会动态修改 result、随机刷新或排序 Offers 的第三方交易系统不可靠。应在快照中存储稳定的 offer identity/成本和原始 result，或在每次 `setOffers` 时明确开始新一代快照。
- `setOffers`/`updateTrades` 注入和 `AbstractVillager.getOffers`/`readAdditionalSaveData` 注入之间存在多次调用同一 helper 的重入机会。当前 `hasOnlySlimeBallResults` 让稳定状态不重复随机生成，但配置关闭和异常恢复必须在每个入口一致处理。
- 当拟态变回普通实体时，`SlimeMimicHandler.setSlimeMimic(false)` 恢复当前 offers 的原始结果；若期间有第三方改过成本、奖励或 offer 顺序，helper 只按索引恢复 result，可能产生混合报价。该行为应在产品规则中明确，不能宣称是无损恢复。

公开 API 替代：NeoForge 的交易相关事件可以覆盖“生成/刷新”，但不存在一个同时覆盖 AbstractVillager 读档、Vanilla `getOffers` 和第三方 setter 的统一报价替换 API；当前两个尾注入仍有必要。Accessor `offers` 本身已在报告 3.2 单独评为低侵入但版本耦合。综合等级：必要性中高，侵入性中等，交易兼容风险 P1/P2。

### 8.9 本批次结论与整改顺序

1. 先修正村民交易 helper 的配置门控：`rewriteSellItems`、load、getOffers、setOffers、updateTrades 五条入口必须共享同一 `rewriteVillagerTrades` 判断；关闭后应恢复/保留原始报价，不再继续写入 Slime Ball。
2. 修复 `MerchantOffer` 复制丢失 `rewardExp` 的问题，并验证成本组件、uses、demand、specialPriceDiff、库存不足和恢复路径；重点测试 Villager 与 Wandering Trader/其他 AbstractVillager 子类。
3. 决定 Haunting 的产品语义：若只是阻止 Horse 转换，保留 Create 的失明、缓慢和客户端效果；若要完全替换，必须把跳过原逻辑写入设计文档并测试粒子、音效和再生不存在/存在两个分支。优先评估 Create `FanProcessingType` registry wrapper，避免直接取消第三方类型方法。
4. 给 PortalForcer 增加明确的“流体是否参与传送”策略，验证 `enablePortalExtraction` 的文案和实际语义是否一致；限制流体候选形态，测试流体扩散导致的意外目的地和标准门被抢占。
5. 评估将 `LivingEntitySlimeMimicMixin` 迁移到 NeoForge 21.1.219 `AttachmentType`：用 serializer 兼容 `CreateBiotechSlimeMimic`，用 sync handler 替代 EntityDataAccessor，并处理死亡/转换和初次客户端同步。
6. 为 Nether Portal BlockEntity 统计成片门的区块/NBT成本，并覆盖门户形成、拆除、重载、抽空、区块卸载和大型实体传送；在没有数据前不要把该 Mixin 评为普通低风险 BlockEntity 注入。

本批只追加了审阅文档，没有修改模组源码，也没有运行编译或客户端验证。Create Haunting 的对照来自本地 `ref/1.21.1/Create` 6.0.10；NeoForge AttachmentType/PlayLevelSoundEvent 的可替代性以本地 NeoForge 21.1.219 sources 为准。

## 9. 第四批：Nozzle、Package、Saw、Spawn Egg 与 Smart Glue

本批覆盖 8 个 Mixin：`NozzleBlockMixin`、`PackageItemCardboardBoxMixin`、`PressingBehaviourSoundMixin`、`SawBlockEntityMixin`、`SmartBlockEntityLegacyRefreshMixin`、`SpawnEggItemMixin`、`MixinSuperGlueSelectionHelper` 和客户端 `MixinSuperGlueSelectionHandler`。对照基线是 `ref/1.21.1/Create` 中 Create 6.0.10 的对应源码，重点检查了 `NozzleBlock`、`PackageItem`、`PressingBehaviour`、`SawBlockEntity`、`SmartBlockEntity` 和 Super Glue 选择流程。

### 9.1 `NozzleBlockMixin`

目标是让 Create 原版 Nozzle 可以连接 `ExperiencePumpBlock`，并在放置于经验泵上时翻转 `FACING`。当前有两个注入：`canSurvive` HEAD 可取消注入，以及 `getStateForPlacement` RETURN 修改返回状态。

必要性：如果产品要求玩家直接使用 Create 的原版 Nozzle，而不是另注册一个经验泵专用喷嘴，那么 Create 原版 `NozzleBlock.canSurvive` 只接受 `IAirCurrentSource`，不能识别当前经验泵。NeoForge 没有“为第三方 Block 的 canSurvive 增加一个条件”的公共事件，Create 也没有 Nozzle 连接谓词注册表，因此针对 `NozzleBlock` 的 Mixin 有实际必要性。

侵入性：放置注入本身较低，它先让 Create 完成默认状态计算，只在紧邻经验泵时修改方向；非经验泵仍完整使用原方法。`canSurvive` 注入则明显更危险：

- 它只检查相邻方块是否是经验泵，以及经验泵 `FACING` 与 Nozzle `FACING` 是否同轴，没有检查经验泵 BlockEntity 是否存在、泵的输出/输入方向是否与 Nozzle 方向一致，也没有复用原版 `IAirCurrentSource` 的 `getAirflowOriginSide` 约束。
- 它先查 `pos.relative(nozzleFacing)`，失败后再查 `pos.relative(nozzleFacing.getOpposite())`，因此同一个 Nozzle 可能在经验泵两侧都被判定为可生存。可是 `ExperiencePumpBlockEntity.hasInputNozzle` 只接受特定的输入侧和精确方向，因而“能存活”不等于“泵能实际抽取”。这会产生视觉上放置成功、机器却不工作的错误状态。
- 对经验泵路径它直接 `setReturnValue(true)`，没有把原版的 Nozzle 生存条件和方向语义作为必要前置。对普通 Create 风扇 Nozzle，条件不命中时仍调用原方法，因此普通风扇玩法没有被直接改写。
- `getStateForPlacement` 的注释称方向与原版相反，但 `canSurvive` 同时兼容两侧，协议并不唯一。旧存档、手动改状态、结构复制和邻居更新可能形成不同方向的经验 Nozzle。

公开 API 替代：没有等价的 Nozzle 连接回调。侵入性更低的架构是注册一个经验泵专用 Nozzle Block，复用 Create 的形状/渲染资源，在该 Block 自己的 `canSurvive` 中检查精确的 `ExperiencePumpBlock` 方向，并让 `ExperiencePumpBlockEntity` 识别该新 Block。代价是新增方块、配方和旧方块迁移，且不能无缝复用已经放下的 Create Nozzle。若保留 Mixin，至少应把判定收窄为“相邻位置存在经验泵 BlockEntity + Nozzle 位于泵输入方向 + `FACING` 精确等于泵输出方向”，不要只比较 Axis。

综合评价：必要性中高，类级侵入中等，当前方向校验风险 P1；普通 Create Nozzle 的原版路径为低风险。

### 9.2 `PackageItemCardboardBoxMixin`

目标是把没有真实 Package contents 的 `CapturedEntityBoxItem` 暴露为一个“包含自身”的虚拟槽位。Mixin 在 `PackageItem.getContents` RETURN 取得 Create 已构造的 `ItemStackHandler`，再调用 `CapturedEntityBoxHelper.applyVirtualSelfFallbackContents`，不改真实 `ItemContainerContents` 数据。

必要性：`CapturedEntityBoxItem` 继承 Create `PackageItem`，这样才能直接进入 Create 的包裹实体、链式运输、Saw 解包和包裹视觉体系。但 Create 的 `getContents` 是静态方法，没有可覆写的实例方法、contents provider 或包裹物品扩展点。若要让一个没有普通物品槽的特殊 Package 在 Create 物流中仍然代表自身，当前 Mixin 是最直接的接入方式。

原逻辑覆盖：对普通 Package，以及带真实 `PACKAGE_CONTENTS` 的箱子，helper 返回原 handler，Create 原逻辑完整保留。对无真实 contents 的自定义箱子，Mixin 有意改变返回契约，而不是在原流程之后追加一个普通物品。因此这不是“无行为改变”的尾注入，而是对 `PackageItem.getContents` 公共静态契约的全局扩展。

影响范围比类名看起来更大。Create 6.0.10 中 `PackageItem.getContents` 的调用者至少包括：

- `SawBlockEntity.applyRecipe`：决定 Saw 解包后的槽位；
- `PackageEntity.dropAllDeathLoot`：包裹实体损坏时生成掉落；
- `PackagerBlockEntity.unwrapBox`：决定拆包机是否认为包裹有内容；
- `PackageRepackageHelper`：汇总包裹内容并重新分包；
- ComputerCraft `PackageLuaObject`：对外报告包裹槽位和物品详情。

因此当前 Mixin 会让这些 Create 子系统都把空的自定义箱子视为“含有一个自身的包裹”。这可能是为了让箱子继续沿 Create 物流运输，但也可能造成拆包机把箱子再次送入目标、再包装汇总出现自引用、ComputerCraft 报告虚构内容等问题。尤其是 `applyVirtualSelfFallbackContents` 当前只判断 `CapturedEntityBoxItem.isBox(box)`，没有判断 `hasCapturedEntity(box)`；一个完全空的纸箱也会获得虚拟自身槽位。若空箱本来应该是普通可堆叠空容器，这是一个明确的语义风险。

兼容性方面，helper 只修改新建的临时 handler，不会直接写入 Package 的 NBT；但任何调用者如果把返回槽位重新写回 Package，就可能把虚拟内容物化。Create、ComputerCraft 或其他模组未来若假定“PackageItem.getContents 只反映 `PACKAGE_CONTENTS`”，该假设会失效。该 Mixin 还依赖 Create 的返回类型 `ItemStackHandler` 和静态方法签名，方法签名改变会直接导致启动注入失败。

公开 API 替代：Create 没有包裹内容提供者 API。若只需要 Saw 释放箱内实体，可以移除全局 fallback，单独在 Saw 适配路径处理；若需要完整 Create 物流兼容，则必须在包裹实体掉落、拆包、再包装、计算机查询等多个入口分别识别自定义箱子，Mixin 数量会增加但每个影响面更小。更彻底的低侵入方案是把自定义箱子设计成独立 Item/Entity 物流系统，但会失去现有 Create Package 兼容性。当前实现可保留，但应至少加入 `hasCapturedEntity` 门控，并在文档中明确“虚拟自身内容是 Create 物流协议的一部分”。

综合评价：必要性中高，单个注入代码量低但协议侵入高，跨模组兼容风险 P1；它不能按普通“低风险 RETURN 修改”处理。

### 9.3 `PressingBehaviourSoundMixin`

目标是在 Creeper Blast Chamber 结构内静音 Mechanical Press 的激活音效。Mixin 对 `PressingBehaviour.tick` 中两个不同描述符的 `SoundEntry.playOnServer` 分别做 Redirect：羊毛带上的 `MECHANICAL_PRESS_ACTIVATION_ON_BELT` 和普通激活的带音量/音调重载。

必要性：Create 6.0.10 没有 PressingBehaviour 的声音策略接口或“是否播放本次激活音效”的公共回调。当前 Mixin 只拦截两个声音调用，不改 `runningTicks`、处理配方、粒子、`sendData` 或完成回调；当行为对象不是 `MechanicalPressBlockEntity`，或结构判断为 false 时，原调用完整执行。这一点符合“添加部分以原逻辑同源”为目标。

兼容性与侵入性：

- `shouldMutePressActivationSound` 每次判断会以 Press 位置为中心扫描配置尺寸的三维范围，并读取多个 BlockEntity。声音本身不是高频逻辑，但大型结构中每个激活周期都会产生一次额外扫描；应确认最大结构尺寸和同一 tick 多台 Press 的成本。
- Redirect 依赖 Create 当前两个 `SoundEntry.playOnServer` 描述符。若 Create 改为 `Level.playSound`、新增声音重载或把声音逻辑移到其他方法，现有 Mixin 可能启动失败或漏静音。
- 该 Mixin 对 Create/第三方其他 Mixin 的同一调用点存在 Redirect 冲突可能；两个重载没有 `ordinal`，目前依赖每个描述符各只有一个匹配点。

公开 API 替代：这是本批最适合迁移到公共 API 的 Mixin。`SoundEntry.playOnServer` 最终调用 `Level.playSound`，可评估使用 NeoForge 的 `PlayLevelSoundEvent.AtPosition`，只在声音为 Create 两个 Mechanical Press 激活事件、位置对应结构内 Press 时取消事件。该方案不会依赖 PressingBehaviour 的内部 tick 方法，也能覆盖 Create 将来从其他入口播放同一激活声音的情况；代价是事件层拿不到“调用者是 PressingBehaviour”的栈上下文，必须以声音 ID、坐标和结构扫描作为判定。若实际 NeoForge 版本的事件无法取消服务端位置音效，再保留当前 Redirect。

综合评价：必要性中等，当前侵入性低到中，非目标 Press 行为影响低，Mixin 风险 P2；优先评估 `PlayLevelSoundEvent.AtPosition` 替代。

### 9.4 `SawBlockEntityMixin`

目标是在 Create Saw 完成处理时释放被纸箱捕获的实体。Mixin 在私有 `SawBlockEntity.applyRecipe()V` HEAD 检测输入，服务端调用 `CapturedEntityBoxHelper.releaseCapturedEntity`，清空 Saw inventory 并取消 Create 原方法。

必要性：Create 的 Saw 配方处理方法是私有的，当前公开 API 没有“自定义输入在 Saw 完成时执行实体生成”的 recipe callback。NeoForge 的普通配方注册也只能返回 ItemStack，不能表达 `Level.addFreshEntity`、实体速度、捕获数据清理等副作用。因此在不创建自定义 Saw BlockEntity 的前提下，该注入基本必要。

原版逻辑覆盖结论：对非捕获箱子，原方法完整执行。对捕获箱子，当前代码有意跳过 Create 的 Package 解包和锯切配方，仅保留调用者在 `tick` 中设置的 `playEvent`、`appliedRecipe`、20 tick 收尾动画和 `sendData`；没有执行 Create 的 `SAW_PROCESSING` advancement，也没有生成普通 item output，这是该功能的预期分支。但该分支目前存在明确的数据丢失问题：

- `releaseCapturedEntity` 返回 `false` 时仍然 `inventory.clear()` 并取消原方法。实体 NBT 损坏、实体类型无法加载、区块/Level 拒绝加入实体或 `addFreshEntity` 失败时，捕获箱会被静默清空。
- 没有检查输入数量。正常捕获箱应为不可堆叠，但损坏 NBT、命令生成或外部模组复制后如果 count 大于 1，当前只释放一个实体却清空整个堆叠。
- `inventory.clear()` 清掉的是整个 ProcessingInventory，而不是只移除输入槽；如果未来 Saw 在多个槽保存输出、过滤器辅助物品或第三方扩展数据，成功释放也可能误清其他槽位。
- 自定义实体出生点和速度没有使用 Create Saw 的 item output 生成辅助逻辑；这是功能需要，但必须验证横向 Saw、正反转、边缘位置、实体碰撞、空区块和客户端虚拟 Ponder 行为。

最低限度修复是只有在 `releaseCapturedEntity(...)` 返回 true 后才清除输入并取消；失败时应继续原方法或保留输入。更稳妥的是只清空成功消费的输入槽，并对 count 进行显式约束。`remap = false` 在这里是可解释的：目标是 Create 私有、无映射 Minecraft 类型的 `applyRecipe()V`；但它仍然高度依赖 Create 私有方法名。

公开 API 替代：若 Create 将来提供 Saw processing event，可迁移到事件；当前没有。创建自有 Saw BlockEntity/Block 可以彻底移除该 Mixin，但需要复制 Create Saw 的锯切、树木、过滤、动画和能力逻辑，实际侵入性更高。当前 Mixin 应保留但评为 P1，先修复释放失败清空问题。

### 9.5 `SmartBlockEntityLegacyRefreshMixin`

目标是识别旧版 `create_biotech:experience_tank`、`experience_pipe` 和 `encased_experience_pipe` BlockEntity NBT，在 `SmartBlockEntity.read` 后的首次 tick 中触发一次保存/客户端更新/光照检查。它服务于 `CBRemapHelper` 将旧注册 ID alias 到 Create Fluid Tank/Fluid Pipe 的迁移链路。

必要性：注册表 alias 能让旧 ID 找到新的 BlockEntityType，但 NeoForge 没有一个只针对第三方 BlockEntity 的“读完 NBT 后立即刷新 Create 内部渲染和邻接状态”的统一公共回调。DataFixer 可以做版本级 NBT 迁移，却不能自然替代当前 alias 加 Create 自身读取逻辑的组合。因此对于兼容旧世界，当前行为有实际价值。

时序方面，`FluidTankBlockEntityMixin` 在读取时把旧 `StoredExperience` 转成 Create 的 `TankContent`，并在原 `read` 尾部恢复 controller 的经验流体；本 Mixin 只在 `SmartBlockEntity.read` HEAD 记录 legacy 标志，随后在 SmartBlockEntity tick 尾部刷新。理论上它不会覆盖 Create 的读取逻辑，且普通 Create BlockEntity 不会触发。但要注意 SmartBlockEntity 的注入发生在 `FluidTankBlockEntity.tick` 的 `super.tick()` 内，早于 Fluid Tank 自己后续的 connectivity、capability 和 boiler 更新；若刷新包或渲染依赖这些后续状态，当前顺序可能提前。

兼容性与风险：

- 目标类是所有 Create `SmartBlockEntity`，每个实例增加一个布尔字段并在每次 `read`/`tick` 经过条件分支。单次成本低，但类级覆盖面远大于三个旧类型。
- 它依赖 NBT 中仍保留旧的 `id` 字符串。若某个 NeoForge/注册表加载阶段已经把 alias 规范化为新 ID，pending 标志不会设置，迁移后的客户端刷新就会丢失。应在真实旧世界上验证传入 `read` 的 tag。
- `read` 可能在实例尚未挂接 Level 时发生，当前通过 tick 延迟处理是合理的；但如果在首次 tick 前又收到一次 server-side read，后一次普通 tag 会把 pending 标志清掉。
- `sendBlockUpdated` 使用旧状态和新状态相同、flags 为 16，和 Create 自己的一些渲染刷新路径一致，但它没有显式调用 `requestModelDataUpdate`；`checkBlock` 也会给不发光的 Fluid Pipe 产生一次额外光照检查。需要验证这两个调用是否真的覆盖经验罐客户端同步/渲染需求，而不是只制造额外工作。

公开 API 替代：优先级顺序应是 DataFixer/版本化数据迁移，其次是针对 `FluidTankBlockEntity`、`FluidPipeBlockEntity` 和 `EncasedPipeBlockEntity` 的窄目标注入或自有加载包装。NeoForge Chunk load 事件可以观察旧数据，但时机晚于 BlockEntity 的标准反序列化，不能可靠替代 `read` 前后的转换。当前 Mixin 可暂留，但建议把判断和刷新拆成只覆盖三个目标类型的实现，避免把所有 SmartBlockEntity 都纳入协议。

综合评价：旧存档兼容必要性高，运行时逻辑侵入中等，目标范围偏大，存档/客户端刷新风险 P1；不能仅因代码只有一个布尔字段而评为低风险。

### 9.6 `SpawnEggItemMixin`

目标是在玩家主手使用 Spawn Egg 且副手持有 Bionic Mechanism 时，把 `CreateBiotechSlimeMimic` 标记预先写入 Spawn Egg 的 `ENTITY_DATA`，调用原版 `EntityType.spawn`，再把生成实体标记为 Slime Mimic。Mixin 分别包装 `SpawnEggItem.useOn` 和 `SpawnEggItem.use` 中的同一 `EntityType.spawn` 重载。

必要性：标记需要在 `EntityType.spawn` 读取实体数据之前进入 ItemStack，才能覆盖出生实体的标准 NBT 初始化；只在 `EntityJoinLevelEvent` 或生成后事件中处理会晚于部分 `readAdditionalSaveData` 逻辑，也无法可靠知道是哪个玩家、哪只手和哪枚 Spawn Egg 触发。当前两个包装点分别覆盖方块上使用和流体中使用的 vanilla Spawn Egg 交互，是比重写整个 SpawnEggItem 更窄的做法。

原版逻辑覆盖：不满足配置、手部、Bionic Mechanism 或 allowlist 时直接 `original.call`，参数完整透传；满足条件时仍调用原 `EntityType.spawn`，只临时替换 `DataComponents.ENTITY_DATA`，并在 `finally` 恢复原组件，因此 Spawn Egg 的扣除、位置、旋转、碰撞和失败返回仍由原版控制。对生成失败的实体，`markSpawnedEntity(null)` 无副作用。

兼容性风险主要来自调用点而非玩法分支：

- 目标是 Minecraft 基础 `SpawnEggItem` 的内部调用和精确描述符。Minecraft 未来若改为其他 spawn overload、拆出 helper 或新增另一条 Spawn Egg 生成路径，可能启动失败或漏掉某类使用。
- 原始 `CustomData` 对象被保存后再次放回 ItemStack。如果第三方在 `EntityType.spawn` 内就地修改了原 `ENTITY_DATA`，这些修改会被当前 finally 恢复逻辑丢弃；标准原版路径通常不会这样做，但应作为兼容假设记录。
- 预写标记和生成后 `setSlimeMimic` 可能让拟态状态经历一次“读档标记 + handler setter”双路径。村民交易、allowlist 和客户端同步必须测试，避免重复重写交易或产生状态短暂不一致。

公开 API 替代：单纯实体生成事件不能等价替代。可以注册自定义 Spawn Egg Item，或用交互事件保存上下文再在生成事件中消费，但前者不能覆盖所有原版 Spawn Egg，后者要引入 ThreadLocal/时序关联，侵入性通常更高。当前 Mixin 是合适的窄接入点，综合等级为必要性中高、侵入性中等、原版影响 P2；应补充方块/流体两种使用、空玩家、生成失败、带原始 ENTITY_DATA、村民和 allowlist 变更测试。

### 9.7 `MixinSuperGlueSelectionHelper`

目标是让 Create 的普通 Super Glue 库存扣除不把 `SmartSuperGlueItem` 当作普通胶水，同时复制 Create 原 `collectGlueFromInventory(Player, int, boolean)` 的搜索和耐久扣除逻辑。

必要性：`SmartSuperGlueItem` 继承 Create `SuperGlueItem`，Create 的公共 helper 只能按 `instanceof SuperGlueItem` 判断，无法通过注册表 Tag 或公开 predicate 排除一个子类。由于 Smart Glue 使用自己的客户端选择器和服务器包，若它参与 Create 的普通 glue selection，会出现两套选择流程同时工作、Smart Glue 被普通胶水流程消耗等问题。因此需要改变分类边界。

当前实现并非只增加一个排除条件，而是完整 HEAD 取消原方法后自行实现。它保留了创造模式、`requiredAmount == 0`、selected slot 优先、背包遍历、耐久计算和 `MAIN_HAND` 耐久破坏；对 Smart Glue 跳过，对其他 `SuperGlueItem` 子类仍处理。普通 Create 的原逻辑主体因而大体同源，但有一个必须明确记录的行为差异：本地 Create 6.0.10 参考中的原方法即使 `simulate == true` 也直接调用 `stack.hurtAndBreak`，当前 Mixin 增加了 `if (!simulate)`，所以改变了普通 Create Super Glue 在预览/模拟调用中的耐久消耗。由于 Create 的客户端选择流程会以 `simulate=true` 检查可用胶水，这很可能是在修复一个上游问题，但仍然是对原版 Create 玩法的改写，不能在报告中称为纯兼容排除。

还应注意，当前循环与 Create 原实现一样会先遍历 selected slot，再遍历完整 `items` 列表；当 `requiredAmount` 大于一枚胶水的剩余耐久时，selected slot 可能被访问两次。这是原逻辑遗留行为，Mixin 没有修复，应在决定是否重写时一并固定测试。

公开 API 替代：最干净的方案是让 `SmartSuperGlueItem` 不再继承 `SuperGlueItem`，改为普通 Item，并在自有 Item/事件中补上 `canAttackBlock` 与“禁用普通方块交互”的行为。这样 Create 的 `instanceof SuperGlueItem`、库存 helper 和客户端 `isGlue` 都会自然排除 Smart Glue，可同时删除本 Mixin 和下一个客户端 Mixin；代价是需要自行复制 Super Glue 的交互保护逻辑。若必须保留继承关系，Create 当前没有 helper predicate 或胶水类型注册 API，Mixin 仍是必要的。

综合评价：功能必要性高，当前覆盖面中等，普通 Create 耐久模拟语义被额外改写，风险 P1/P2；应在报告和变更日志中明确这是“排除 Smart Glue + 修正 simulate 语义”的双重责任。

### 9.8 客户端 `MixinSuperGlueSelectionHandler`

目标是让 Create 原版 `SuperGlueSelectionHandler.isGlue(ItemStack)` 对 Smart Glue 返回 false，从而把输入和选择状态交给 `SmartSuperGlueClientHandler`/`SmartSuperGlueSelectionHandler`。

必要性：Smart Glue 客户端 handler 通过 NeoForge `InputEvent.InteractionKeyMappingTriggered` 自己处理 use/attack；如果 Create handler 仍把 Smart Glue 识别为普通胶水，两个 handler 都会 tick、绘制 outline、响应点击并发送不同的服务器包。当前 Mixin 在 Create 私有 `isGlue` 的 HEAD 只对 Smart Glue短路，其他 Item 完整返回原逻辑，目标清晰且不会改变普通胶水行为。

侵入性与兼容性：这是客户端-only、单方法、单类型判断的低范围 Mixin，但依赖 Create 私有方法名和 `SuperGlueItem` 的继承设计。它不能防止第三方另行按 Item tag 或 `ItemStack` 处理 Smart Glue；它只保证 Create 自己的 handler 不进入。若删除它而仅依赖事件优先级，Create 和模组两个事件订阅者的执行顺序不应作为协议，仍可能出现重复输入。

公开 API 替代：如果采用上一节的“Smart Glue 改为普通 Item”方案，本 Mixin 可直接删除；Create 的 `instanceof SuperGlueItem` 会自然返回 false。保留子类时，NeoForge 没有为 Create 私有 handler 提供过滤回调，当前注入是必要的。综合评价：必要性中高，侵入性低，原版影响低，风险 P2。

### 9.9 本批次结论与整改顺序

1. 立即修复 `SawBlockEntityMixin`：释放失败不得清空输入；成功时只消费明确的一个箱子，并覆盖损坏 NBT、`addFreshEntity` 失败、堆叠输入和其他槽位。
2. 收紧 `NozzleBlockMixin` 的经验泵方向协议，要求精确输入侧和有效 BlockEntity；不要继续用“同轴 + 两侧兼容”作为生存条件。
3. 为 `PackageItem.getContents` 写出正式的虚拟内容契约：确认空箱是否也应自引用，确认拆包、包裹掉落、再包装和 ComputerCraft 是否都需要该行为；若不是，移除全局 fallback，改为 Saw/自有物流的窄入口。
4. 优先验证 NeoForge `PlayLevelSoundEvent.AtPosition`，将 Press 静音从 Create 内部 tick Redirect 迁移到声音事件；如果事件不能可靠取消服务端音效，再保留当前 Mixin。
5. 决定 Smart Glue 的继承策略。若没有必须继承 `SuperGlueItem` 的行为，改成普通 Item 并自行补交互保护，可同时删除两个 Super Glue Mixin；若保留继承，保留当前排除逻辑并单独记录 `simulate` 修正。
6. 将 `SmartBlockEntityLegacyRefreshMixin` 的全体 SmartBlockEntity 目标收窄到三个旧经验流体类型，或用 DataFixer 覆盖旧 NBT；验证 alias 后 `read` 仍能看到旧 ID，以及 flags 16 是否真正完成客户端同步。
7. 对 `SpawnEggItemMixin` 做方块使用、流体使用、原始 ENTITY_DATA、生成失败、村民交易和服务器/客户端同步回归测试；该 Mixin 当前不建议用事件层强行替代。

本批仍只追加了审阅文档，没有修改模组源码，也没有运行编译或客户端验证。报告结论以当前工作区源码、`gradle.properties` 的 Minecraft 1.21.1/NeoForge 21.1.219/Create 6.0.10-281 版本基线，以及 `ref/1.21.1/Create` 对应参考源码为准。

## 10. 第五批：客户端渲染、摄像机、Ponder 与动画

本批覆盖 13 个客户端 Mixin，以及它们依赖的 `CreeperAccessor`、`ModelPartAccessor` 和 `SlimeMimicRenderLayer`。基线为 Minecraft 1.21.1、NeoForge 21.1.219、Create 6.0.10-281、Ponder 1.0.82 和 Flywheel 1.0.6。客户端 Mixin 不直接改变服务端存档或网络协议，但它们大多位于全局渲染类、渲染线程状态或模型底层，兼容性风险不能只按“没有服务端副作用”评估。

### 10.1 `BasinRendererMixin`

目标是包装 Create `BasinRenderer.renderSafe` 中对 `IItemHandlerModifiable.getStackInSlot` 的调用，把 `BasinEntityProcessing.isCapturedSmallSlimeItem` 识别到的控制物品返回为 `ItemStack.EMPTY`。它同时覆盖 Create 原 renderer 中的“统计非空物品数量”和“逐槽取物品实际渲染”两个调用点。

必要性：捕获的小型史莱姆物品只是实体处理状态的可视化占位，实际史莱姆由 `BasinEntityProcessing` 作为实体保存在 Basin 内。如果让 Create 的 Basin renderer 把占位物品正常渲染出来，客户端会同时看到普通物品和实体，且物品数量会错误参与 Create 的环形布局。Create 6.0.10 的 Basin renderer 没有物品显示 predicate、renderer callback 或可注册的槽位过滤器，因此在保持 Create 原 Basin/BlockEntity 不变的前提下，这个注入点是合理的最小入口。

原版逻辑覆盖：对普通物品、普通 Basin、空槽以及非目标 `ItemStack`，handler 完整调用原操作并返回原值；对目标物品，只改变客户端显示值，不触碰 inventory、配方匹配、输出动画或实体数量。由于统计和实际渲染都走同一个 wrapper，剩余普通物品的数量和布局应当保持相对一致，而不是只把模型隐藏后留下错误角度分区。

兼容性与风险：

- 目标描述符包含 NeoForge `IItemHandlerModifiable` 和 Minecraft `ItemStack`，当前没有设置 `remap = false`，符合项目的映射约定；但它仍依赖 Create 当前 renderer 的调用方式。如果 Create 改用 `IItemHandler`、缓存槽位或自有显示列表，注入可能启动失败或漏掉显示路径。
- 当所有槽位都被隐藏时，Create 原方法仍会计算 `360f / itemCount`，此时 `itemCount == 0`。Java 浮点除法只产生无穷值，不会立刻抛异常，且后续没有可渲染槽位，所以通常只是无效计算；应把“全是捕获物品”的 Basin 作为回归用例，避免未来 Create 对角度计算改为整数或新增无条件使用该值。
- 这个 Mixin 只隐藏 item capability 路径。若 Create 将捕获物品加入 `visualizedOutputItems`，输出阶段仍可能按独立列表显示，这是两个不同的视觉语义，不能据此认为该 Mixin 覆盖了全部 Basin 显示。

公开 API 替代：Create 当前没有针对 Basin 物品显示的公开过滤 API。可以注册自有 Basin renderer 并完整复制 Create 的 fluid、item、输出动画逻辑，但这会比单个条件 wrapper 更侵入，且容易漏掉未来 Create 改动。若未来 Create 暴露 item display predicate，应迁移；在当前版本保留 Mixin 更稳妥。

综合评价：必要性中高，侵入性低，普通玩法影响低，Create 版本耦合中等，风险 P2。

### 10.2 `CameraMixin`

目标是注入 Minecraft `Camera.setup` 的返回点。当摄像机是本地玩家的第一人称摄像机且配置开启时，查询 `ShulkerTeleporterClientEvents.getFirstPersonCameraOffset`，将摄像机位置加上闭合外壳产生的局部偏移。第三人称、反向第三人称和其他实体摄像机不进入该分支。

必要性：目标需求是改变摄像机的三维位置，而不是 yaw、pitch 或第三人称距离。NeoForge 的 `ViewportEvent.ComputeCameraAngles` 只提供角度修改；当前没有一个等价的公开“在 `Camera.setup` 完成后修改 position”事件。把 offset 加在 `setup` 之后也能保留原版第一人称摄像机的基础位置计算，因此当前注入点比重写整个摄像机 setup 更窄。

原版逻辑覆盖：所有条件不满足时原版 `Camera.setup` 完整执行，Mixin 不取消方法，也不改变第三人称碰撞距离、视角旋转或摄像机实体。满足条件时只在原位置上增加模组自己的偏移，符合“附加变换而不是复制原版摄像机逻辑”的原则。

兼容性与性能：

- `setup` 是每帧路径。`getFirstPersonCameraOffset` 会通过 `SubLevelCompat.forEachIncludingEntitySpace`，并在每个候选空间扫描约 `2 x 6 x 2` 的方块位置；即使没有传送器，也会执行扫描。单次扫描不一定昂贵，但在高帧率、多个子空间或区块访问较慢时会成为持续的客户端开销。
- offset 添加在原版摄像机位置求解之后。如果传送器偏移被配置为非零，第三方依赖摄像机位置做遮挡或视锥判断的逻辑会看到修改后的位置；这是该功能预期的一部分，但应测试闭合外壳、传送器嵌套空间和玩家位于边缘时的裁剪。
- `require = 0` 可以在目标方法签名改变时让功能静默失效，降低启动崩溃概率，但也会隐藏版本不兼容。项目的 Create/Minecraft 版本范围很窄，建议至少在开发日志中报告注入缺失。

公开 API 替代：当前没有等价的位置事件。可以在客户端 tick 中缓存“当前相关传送器”，渲染帧只用 partial tick 插值高度和做轻量有效性检查，以减少每帧空间扫描；这属于实现优化，不是去除 Mixin。若未来 NeoForge 暴露 camera position event，优先迁移。

综合评价：必要性高，侵入性窄，原版玩法影响低，持续性能开销 P2；Mixin 本身可保留，优先优化候选查询缓存。

### 10.3 `ClientLevelMixin`

目标是让客户端 `ClientLevel` 实现 `ShulkerPackagerPlacementCapture`，在 `ShulkerPackagerItem.place` 成功预测放置后，读取当前 `BlockStatePredictionHandler.currentSequence()`，将选择列表和该预测序列交给 `ShulkerPackagerConnectionHandler`。序列号用于把一次客户端预测放置和随后发送的配置请求对应起来。

必要性：`ShulkerPackagerItem` 是 common 代码，不能直接依赖只在客户端存在的连接处理器；而捕获动作必须发生在原版/NeoForge 的客户端预测放置成功之后，才能确认方块真的放下并冻结准确的选择快照。普通 `PlayerInteractEvent` 既不能可靠地知道放置是否成功，也不能自然拿到同一预测序列。通过一个很小的 common interface 作为客户端桥接，功能边界是清楚的。

低侵入优化：当前实现 Shadow 了 `ClientLevel` 的私有 `@Final blockStatePredictionHandler` 字段。活动 1.21.1 的映射中，`ClientLevel.getBlockStatePredictionHandler()` 是公开方法，`BlockStatePredictionHandler.currentSequence()` 也有公开映射（可由 `build/moddev/artifacts/intermediateToNamed.srg` 中的对应条目确认）。因此实现接口时可以直接通过 `(ClientLevel) (Object) this` 调用公开 accessor，删除字段 Shadow；这不会删除 Mixin，但能避免依赖私有字段名和字段布局。

兼容性与原版覆盖：Mixin 不改 `ClientLevel` 原有方法、不改预测状态，只增加一个接口方法。`ShulkerPackagerItem` 仍在 `super.place` 成功、目标状态正确时调用它；手持物品、放置消耗、客户端回滚和服务端校验仍由原版/NeoForge负责。需要测试没有预测序列、连续快速放置、放置失败、切维度以及客户端回滚后过期快照清理。

公开 API 替代：可以尝试在 common item 中通过 DistExecutor 调用客户端 helper，或使用更晚的客户端方块更新事件，但前者需要严格隔离 client class，后者不能稳定关联预测序列。当前 interface + ClientLevel Mixin 是低侵入方案；建议只把私有字段访问改成公开 accessor。

综合评价：必要性中高，新增行为很窄，原版影响低，当前私有字段耦合为 P2；改成公开 accessor 后可降到 P3。

### 10.4 `CreeperRendererMixin` 与 `CreeperAccessor`

目标类是所有 `LivingEntityRenderer`，在 `render` HEAD 根据 `CreeperBlastChamberBlockEntity.getClientWorkingCreeperCompression` 对 Creeper 的 PoseStack 做水平扩张、垂直压缩；在 Ponder 压缩时还通过 `CreeperAccessor` 临时替换 Creeper 私有 `oldSwell`/`swell` 字段，使 Creeper 自身 renderer 使用模组同步的脉动值。RETURN 注入负责恢复 PoseStack 和 Creeper 字段。

必要性：Creeper 的膨胀动画和模型缩放发生在 Create Biotech 需要保留的通用 `LivingEntityRenderer` 路径中。NeoForge 没有一个能够同时修改任意 Creeper renderer 的模型 PoseStack 和 `swell`/`oldSwell` 私有输入的公共 callback。专门注册一个 Creeper renderer 可以避免通用类注入，但必须复制原版 Creeper renderer、层、阴影、名称牌和第三方 renderer 兼容逻辑，实际侵入性更高。

原版逻辑覆盖：压缩值小于等于零、目标不是 Creeper 或 Ponder 数据不存在时，HEAD 直接返回，原 `LivingEntityRenderer.render` 完整执行。正常压缩时原方法仍然执行，只在其外层增加 scale；Ponder 的 swell 修改也在 RETURN 恢复，意图上不改变持久实体状态或服务端数据。`CreeperAccessor` 只提供私有字段 getter/setter，不改变实体 tick 或同步协议。

但当前实现存在高优先级的渲染线程状态风险：

- 若原始 `render` 或其他 renderer/layer 抛出异常，`@Inject(at = RETURN)` 不会替代 Java 的 `finally`。`poseStack.popPose()` 不执行，`CREATE_BIOTECH_TRANSFORM_DEPTH` 和可能的 `CREATE_BIOTECH_SAVED_SWELL` 留在当前渲染线程；后续实体渲染可能继续使用错误深度，最终出现 PoseStack 泄漏、下溢或错误恢复旧 Creeper swell。
- `CREATE_BIOTECH_TRANSFORM_DEPTH` 是全局 ThreadLocal 计数，而保存的 swell 只有一个数组，不按实体或调用帧建立栈。如果渲染过程中发生嵌套 `LivingEntityRenderer.render`，内层压缩 Creeper 会覆盖外层保存值；内层非 Creeper 的 RETURN 还可能看到外层 depth 并错误执行一次 pop。正常 vanilla 路径未必触发嵌套，但其他模组的自定义层足以使这个假设不稳。
- 目标是 `LivingEntityRenderer` 而不是 Creeper 专用 renderer，所有实体的每次 render 都会经过两个注入，虽然后续条件很快返回，但影响面和与其他渲染 Mixin 的冲突面都较大。

公开 API 替代：没有可直接设置 `LivingEntityRenderer` 模型缩放和 Creeper swell 输入的等价 API。推荐保留窄功能但把作用域改为一个带 `try/finally` 的方法包装，或把 PoseStack/swell 操作包装在实际模型渲染调用周围；不要依赖 HEAD/RETURN 注入组合来模拟 finally。保存状态应使用按调用嵌套的记录栈，并校验当前实体和当前 PoseStack作用域。

综合评价：功能必要性高，目标类级侵入高，普通玩法逻辑覆盖意图正确但异常路径不完整，风险 P1。修复清理和嵌套状态之前不应评为低风险客户端美化。

### 10.5 `FlapStuffsMixin`

目标是修改 Create `FlapStuffs.renderFlaps` 和 `commonTransform`。当 `BeltSurfaceRenderScope.current()` 存在时，`renderFlaps` 在原有 flap 变换外再围绕方块中心施加表面倾斜；`commonTransform` 则取消原返回值，调用 `BeltSurfaceRenderScope.tiltedCommonTransform` 生成同源矩阵。没有作用域时两个方法都保留 Create 原逻辑。

必要性：Create 的两个静态 geometry helper 没有接收 FunnelBlockEntity 或 BeltSurface 参数，而 sideways/vertical belt funnel 的倾斜方向只存在于方块状态。要让原版 BER 的 flap 和 Flywheel visual 使用同一 surface transform，必须在调用链中传递上下文。当前 Mixin 只在上下文非空时覆盖，几何公式又直接镜像 `ref/1.21.1/Create` 的 `FlapStuffs` 代码，逻辑同源性较好。

侵入性与兼容性：

- `FlapStuffs` 是 Create 的全局静态类。任何在 Funnel scope 存活期间调用它的其他 Create/第三方路径都会看到倾斜，虽然当前已知调用主要是 Funnel renderer。它不是只绑定某个 Funnel 实例的局部 hook。
- `BeltSurfaceRenderScope.CURRENT` 是单值 ThreadLocal，而不是可嵌套的 scope 栈。`push` 在传入 canonical surface 时不清除旧值；如果之前的 scope 因异常残留，之后的普通 Funnel 也可能继承错误倾斜。
- `renderFlaps` 的 HEAD 只有在当前不为空时才 `pushPose`，RETURN 又重新读取当前值决定是否 `popPose`。如果 nested render 或异常改变了当前值，是否 push 和是否 pop 不再成对；最坏情况下会出现 PoseStack 下溢，或在异常后影响同一线程后续渲染。
- `commonTransform` 是 cancellable HEAD 注入，倾斜分支不调用原方法。当前 helper 保留了原始中心平移、水平旋转、X 偏移和 baseZOffset，但 Create 后续新增的公共变换将被静默跳过。

公开 API 替代：`FlapStuffs` 的方法本身是公开的，但没有传递 surface 上下文的 overload。若能为 Create Funnel 注册自定义 BER/Visual，可直接读取 BlockState 并调用自有 transform，从而删除全局静态 Mixin；当前 Create 的 `AllBlockEntityTypes.FUNNEL` 通过 Registrate 内部注册 renderer/visual，没有看到稳定的第三方替换接口。现阶段 Mixin 基本必要，但应把 scope 改成 push 返回 token、用 `try/finally` 保证清理，并明确只允许 Funnel renderer 使用。

综合评价：必要性中高，目标类全局侵入中高，非目标玩法影响低，渲染状态泄漏风险 P1/P2。

### 10.6 `FluidTankRendererMixin`

目标是在 Create `FluidTankRenderer.renderSafe` 调用 Catnip `FluidRenderHelper.renderFluidBox` 时识别经验流体。当客户端配置关闭“按普通流体渲染”且流体是本模组或 Create Enchantment Industry 的经验流体时，尝试以经验球模型渲染；成功后跳过原 fluid box，失败则回退原 fluid box。

必要性：这是一个“同一种流体在特定容器 renderer 中使用另一种几何”的需求。FluidType 客户端扩展可以提供纹理、颜色、雾和 still/flowing 表现，但不能让 Create Tank renderer 把流体体积改成多个经验球。Create 6.0.10 没有 FluidTank renderer callback 或 fluid-specific render provider，除非复制并替换整个 Tank renderer，否则当前 wrapper 是最窄的可用入口。

原版逻辑覆盖：配置开启、非经验流体或类型不是 `FluidStack` 时调用原操作；经验球自定义渲染返回 false 时调用原操作；自定义渲染成功时只按配置有意改变视觉。Create 的 Tank controller 判断、流体液面、锅炉渲染、PoseStack push/pop 均仍由原 renderer 控制。

当前主要问题是异常边界过宽：

- 自定义 renderer 用 `catch (Throwable)`，仅重新抛出 `ThreadDeath` 和 `VirtualMachineError`，会吞掉 `AssertionError`、`LinkageError` 之外的错误以及其他严重客户端错误，然后继续以普通流体渲染。这可能把 Create、Catnip、资源包或驱动相关的真正缺陷变成只出现一次 warning 的“无害失败”，并掩盖崩溃根因。
- fallback 的原始 `renderFluidBox` 也被 `catch (Throwable)` 包住。发生任何 Create/Catnip 渲染错误时，Mixin 会直接抑制当前帧，而不是让 SafeBlockEntityRenderer 或 Minecraft 的错误处理观察到异常；如果异常发生在 PoseStack/VertexConsumer 的中间状态，当前代码没有能力恢复部分写入。
- `@At` 的目标只写了 `FluidRenderHelper;renderFluidBox`，没有精确描述符。当前 `renderSafe` 中只有一个匹配调用，但未来同一方法增加 overload/第二个调用时，注入范围可能扩大或产生歧义。该目标 owner 是 Catnip，`remap = false` 是合理的；仍应把 descriptor 收窄到活动版本实际的擦除签名。
- 经验流体判断通过注册表 ID，能覆盖 CEI 兼容流体，但任何同名替换、流体代理或注册表重映射都会影响视觉选择；这属于 helper 的协议，应写入兼容说明。

公开 API 替代：没有等价的 Create Tank 级公开扩展点。可以自定义 FluidType 的纹理，但那只能实现“经验流体样式”，不能实现经验球体积表现；也可以注册自有 Tank renderer，但会复制 Create 的 controller、锅炉和窗口逻辑。建议保留 Mixin，同时只捕获自有经验球 renderer 可预期的 `RuntimeException`，不要吞掉原 Create fallback 的任意 `Throwable`，并把渲染失败计数/日志和资源重载策略分开。

综合评价：必要性高，注入范围窄，原版正常路径覆盖较完整，但错误路径会吞错，风险 P1。宽泛异常捕获是本批必须整改的项目。

### 10.7 `FunnelRendererMixin`

目标是在 Create `FunnelRenderer.renderSafe` HEAD/RETURN 设置和清理 `BeltSurfaceRenderScope`，让随后静态执行的 `FlapStuffs` helper 能看到当前 Funnel 的 `BlockState`。它不直接替换 Create renderer 的 flap、过滤器或基础模型。

必要性：Create 原 renderer 的 flap 调用只传入 PoseStack、buffer、pivot、facing 等参数，不传 `FunnelBlockEntity`；当前 sideways surface 只能通过外层 scope 把 block state 传给 `FlapStuffsMixin`。NeoForge 没有“为第三方既有 BlockEntityRenderer 注入 render context”的公共 API。自定义 BER 可以消除该 Mixin，但需要复制 Create `FunnelRenderer` 的基础渲染逻辑。

原版逻辑覆盖：普通 canonical Funnel 的 `push` 不设置倾斜值，原 `renderSafe` 仍完整执行；倾斜 Funnel 也只是添加一个短生命周期的渲染上下文，Create 原方法本身没有被取消或重写。服务端行为、Funnel 取放物品和 Create Visual 路径不受该 Mixin 直接影响。

风险与公开替代：它与 `FlapStuffsMixin` 共用单值 ThreadLocal，因此继承后者的异常清理和嵌套问题。当前 RETURN 不是 `finally`，render 中任何异常都会让 scope 遗留；而 `BeltSurfaceRenderScope.push` 对 canonical 值不主动清理，会放大遗留影响。若后续改造 scope 栈，应将该 Mixin 与 `FlapStuffsMixin` 一起修改，不能只修一端。当前没有稳定的第三方 renderer 替换 API，保留是合理的。

综合评价：必要性中高，功能注入窄但作用域管理脆弱，原版玩法影响低，风险 P1/P2。

### 10.8 `FunnelVisualMixin`

目标是包装 Create `FunnelVisual` 构造器中对 `FlapStuffs.commonTransform` 的调用。若方块状态是 canonical surface，完整调用原 operation；否则根据捕获到的 `FunnelBlockEntity` 状态生成倾斜 common transform，供 Flywheel 的四个 flap instance 使用。

必要性：`FunnelRendererMixin` 只能覆盖非 Flywheel 的 BER 路径；Create 6.0.10 的 `FunnelVisual` 在构造器中直接把 common transform缓存进实例，后续 beginFrame 只更新 flap angle。因此必须在构造时覆盖这一次矩阵，否则支持 Flywheel 的客户端会出现实体/实例渲染方向不同。目标函数是公开的，但没有状态参数，当前包装点是最小改动。

原版逻辑覆盖：无倾斜状态完整调用原 operation；倾斜状态只替换 `commonTransform`，不改 flap 数量、动画、光照、删除或实例收集。`BeltSurfaceRenderScope.tiltedCommonTransform` 保留 Create 原方法的平移、中心旋转和 `X_OFFSET`，逻辑来源明确。

兼容性：构造器描述符、`@Local FunnelBlockEntity` 和调用 ordinal 依赖 Create/Flywheel 当前字节码。Create 的 BlockEntity visual supplier 在 `AllBlockEntityTypes.FUNNEL` 内通过 Registrate 注册，参考源码没有稳定的第三方 visual replacement hook；自定义 block/BE/visual 是更低耦合但更大范围的架构改动。当前注入没有 ThreadLocal 或异常泄漏问题，风险低于 renderer scope。

映射方面，目标描述符包含 `BlockPos`、`Direction` 等 Minecraft 类型，当前显式 `remap = true` 是正确的，不应改成 `remap = false`。

综合评价：必要性中高，侵入性中等，非目标玩法影响低，版本字节码风险 P2。

### 10.9 `GoggleOverlayRendererMixin`

目标是注入 Create `GoggleOverlayRenderer.proxiedOverlayPosition` 的 HEAD：先把 Creeper Blast Chamber 结构内任意命中的位置代理到 chamber controller，再把 Evoker Enchanting Chamber 的上半块代理到下半块。没有特殊结构时让 Create 原方法继续执行。

必要性分析需要区分两条路径。Create 6.0.10 已有公开 `IProxyHoveringInformation` 接口，`proxiedOverlayPosition` 会直接检查 `targetedState.getBlock() instanceof IProxyHoveringInformation`。因此 Evoker 上半块完全可以让 `EvokerEnchantingChamberBlock` 实现该接口，返回 `pos.below()`，这一部分不需要 Mixin。

Creeper Blast Chamber 则更复杂：当前 helper 允许命中 chamber 体积内的多个自有方块、Create Mechanical Press、包裹机和其他结构位置，并通过客户端已加载 controller 集合寻找所属结构。只让 controller block 实现接口不能完整覆盖当前玩法；若要保持“结构任意部分都显示 controller 信息”，必须为所有相关自有结构块提供代理，并另行处理无法修改的 Create 方块，或者改用自有结构代理方块。因而当前全局 Mixin 仍有功能必要性，但不是最小侵入实现。

当前影响与风险：

- `findGoggleInformationSource` 在每次 Create 计算悬浮信息位置时都可能遍历当前 Level 的全部已加载 chamber controller，和命中方块是否属于目标结构无关。大型基地中 chamber 数量增加后，这是不必要的每帧 O(n) 扫描。
- `CLIENT_LOADED_CHAMBERS` 是 `HashSet`，多个有效结构体积重叠时返回哪个 controller 取决于迭代顺序，可能导致信息来源不稳定。原逻辑没有用距离、结构大小或命中方块类型做消歧。
- 对非特殊目标，原方法完整保留；对 chamber/Evoker 分支，返回位置改变是功能本意，不改变服务端玩法，但可能影响其他客户端 Mixin 对原命中位置的判断。

公开 API/优化路线：立即把 Evoker 分支迁移到 `IProxyHoveringInformation`；Creeper 分支至少先按 BlockState 做快速白名单，再调用 controller resolver，避免所有目标都扫描。若能把 chamber 所有可命中结构块改为实现公开接口，则可删除对应的全局查找逻辑；对 Create Mechanical Press 等无法直接修改的块，当前版本仍需要窄 Mixin 或改造结构设计。不要用一个全局 `proxiedOverlayPosition` 扫描代替逐块代理。

综合评价：Evoker 部分可直接优化，Creeper 部分必要性中高，当前全局扫描和重叠结构歧义为 P1/P2；原版非目标覆盖完整。

### 10.10 `LivingEntityRendererMixin`

目标是在通用 `LivingEntityRenderer.render` 调用 `EntityModel.renderToBuffer` 时，为可见的 Slime Mimic 建立 `SLIMEIFY_MODEL_PARTS` 上下文。原模型的 `renderToBuffer` 仍被调用，但其中的 `ModelPart.render` 会被 `ModelPartRenderMixin` 截获并递归输出史莱姆内外壳，而不是输出原实体模型。

必要性：拟态状态可以挂在任意 LivingEntity 上，不能为每一种原版/第三方实体分别注册 renderer。NeoForge 的 RenderLiving event 可以取消或观察渲染，但没有一个通用的“只替换基础 EntityModel、保留原 renderer 的层/阴影/名称牌/动画时序”的公开接口。当前 Mixin 是实现任意 LivingEntity 拟态外观的直接入口。

原版逻辑覆盖：非拟态或隐身实体完整调用原操作；拟态实体仍由原 renderer 负责旋转、动画准备、层绘制和其他 renderer 生命周期，只把基础模型的 vertex 输出交给 `SlimeMimicRenderLayer` 的递归渲染。`try/finally` 确保基础模型抛异常时上下文出栈，这一点比 Creeper 的 HEAD/RETURN 组合可靠。

侵入性与兼容性：

- 目标是所有 `LivingEntityRenderer`，每次基础模型渲染都会进入全局 `ModelPart` Mixin；其他模组若在这个调用过程中嵌套渲染另一实体，ThreadLocal context 可能把嵌套实体也当作当前拟态模型处理。
- 模型递归不调用原 `ModelPart.render`，而是读取 cubes/children、按原 part transform 重建几何。它保留 visible/skipDraw 和层级，但不保留原 renderer 传入的颜色语义，史莱姆内外层使用固定颜色是功能设计；带有自定义 VertexConsumer、特殊 shader 或非标准 ModelPart 的实体需要单独验证。
- `lookupTextureLocation(entity)` 通过当前 dispatcher 取得实体纹理，资源重载时由 `CreateBiotechClient` 清理 `NativeImage` 缓存；资源重载与渲染并发、第三方 renderer 临时纹理和动态纹理仍是风险点。

公开 API 替代：只注册 RenderLayer 是公开且低侵入的，但无法阻止原基础模型先绘制；取消整个 RenderLiving 事件后自行重绘则必须复制所有 LivingEntityRenderer 行为。对于少量实体可以注册专用 renderer，但不能覆盖“任意 LivingEntity 拟态”的需求。当前 Mixin 可保留，不过应为 render context 绑定实体/renderer，避免嵌套渲染串用。

综合评价：功能必要性高，目标类级侵入高，非拟态原版路径完整，模组间渲染兼容风险 P1/P2。

### 10.11 `ModelPartRenderMixin` 与 `ModelPartAccessor`

`ModelPartRenderMixin` 在 `ModelPart.render(PoseStack, VertexConsumer, int, int, int)` HEAD 调用 `SlimeMimicRenderLayer.interceptModelPart`；拦截返回 true 时取消原 ModelPart 绘制。`ModelPartAccessor` 则暴露私有 `cubes` 和 `children`，供 `renderPartRecursive` 按原层级重建史莱姆几何。

必要性：`LivingEntityRendererMixin` 只在 EntityModel 层建立上下文，真正的模型树输出发生在任意深度的 `ModelPart.render`。Minecraft 当前没有一个公开的“以每个 Cube 的 UV、尺寸和 child hierarchy 替换 vertex 输出”的 renderer API；如果不注入 ModelPart，就只能为每个模型类型实现独立模型转换。Accessor 比运行时反射读取 `cubes`/`children` 更稳定，且不修改字段内容。

原版逻辑覆盖：没有上下文时返回 false，原 `ModelPart.render` 完整执行；`SLIMEIFY_MODEL_PARTS` 时递归遍历 visible part、按 `translateAndRotate` 复制 part transform，并对每个 cube 生成史莱姆内外壳；`INTERNAL_RENDER_DEPTH` 用于让内外壳模型自身调用回到原版绘制。`SKIP_MODEL_PARTS` 时直接返回 true，原模型完全不绘制。

这里有一个当前控制流中的明确问题：`SlimeMimicRenderLayer.render` 在第 88-93 行进入 `SKIP_MODEL_PARTS`，随后 `renderFallbackOverlay` 在第 234-239 行调用 `model.renderToBuffer`。因为这个调用没有包在 `runWithoutPartInterception` 中，所有 ModelPart 都会被 `SKIP_MODEL_PARTS` 在 HEAD 取消，`renderToBuffer` 不会向 `overlayConsumer` 写入任何顶点。因此 fallback overlay 按当前实现很可能完全不输出，而不是在史莱姆外再叠加原实体纹理。这个问题不会影响非拟态实体，但会使拟态的平面/透明纹理补偿失效，应评为 P1，并在修复后重新检查透明模型、平面 Cube 和资源包纹理。

其他风险：

- `@Mixin(ModelPart.class)` 是极宽目标；即使每次只做 ThreadLocal 检查，也会覆盖所有实体、方块实体模型和 GUI 中使用的 ModelPart。`RENDER_CONTEXTS.get()` 会在没有 context 的渲染线程首次访问时创建空 deque，生命周期通常可接受，但不应在其他线程大量调用模型渲染。
- `SKIP_MODEL_PARTS` 不只跳过当前 parent model，也会跳过作用域内的任何嵌套 ModelPart。修复 fallback 时应使用 `runWithoutPartInterception`，并确保 internal depth 不会使真正的其他实体模型绕过/误入当前上下文。
- `SlimeMimicRenderLayer` 为 flat cube 读取 `ModelPart.Cube`、Polygon 和 Vertex 的私有字段并以字段类型/数量推断结构；这不是 Mixin 本身，但与底层 ModelPart Mixin 共同构成显著版本风险。反射失败会永久禁用修正并退回 vanilla cube 编译，部分已写入 vertex 后再 fallback 还可能造成重复或半成品几何。

公开 API 替代：没有保持任意实体模型、原 UV 和递归层级的等价公开 API。可用专用 renderer/model 重写，但范围更大；Accessor 和 ModelPart 注入是当前需求下的必要低层接入。优先修复 fallback context，并把 context 从裸 ThreadLocal 改成带实体、renderer 和嵌套深度的记录。

综合评价：必要性高，ModelPart 类级侵入高，非拟态原版调用保持完整，但拟态 fallback 当前存在明确视觉缺陷，风险 P1。

### 10.12 `PressingBehaviourMixin`

目标是客户端 `PressingBehaviour.getRenderedHeadOffset(float)`。当该行为属于 Create `MechanicalPressBlockEntity` 时，返回 `CreeperBlastChamberBlockEntity.getSynchronizedPressHeadProgress`；对于不属于有效 chamber 的 Press，该 helper 会回退到按当前 PressingBehaviour 计算的本地动画进度。Create 的 `MechanicalPressRenderer` 和 `PressVisual` 都调用这个公共方法，因此一次注入同时覆盖非 Flywheel 和 Flywheel 两条渲染路径。

必要性：Creeper Blast Chamber 内多台 Press 需要以 master Press 的 phase 同步渲染，但 Create 没有“替换 Press 动画时钟”的公共 callback，且两个 renderer/visual 入口都最终经过该方法。直接替换 Press renderer/visual 会复制 Create 的 shaft、head、light 和实例生命周期逻辑，当前 Mixin 更窄。

原版逻辑覆盖：无效结构、非 chamber Press、没有 controller 映射或 chamber 不可用时，helper 的 `getLocalPressHeadProgress` 基本镜像 Create 6.0.10 的 `getRenderedHeadOffset`：包含 running 判断、`Math.abs(runningTicks)`、160 tick 分段、三次曲线和回落曲线；之后 Create 原 renderer/visual 仍负责乘以 `mode.headOffset`。有效 chamber 只替换 phase 来源，不改服务端 `runningTicks`、配方或声音。

兼容性与优化：

- 目标方法是 Create 自有公共成员，描述符只有 primitive `float` 和 `float` 返回值，不包含需要 remap 的 Minecraft 类型；`remap = false` 在此处有依据。若 Create 把逻辑移到 Visual 自身私有 helper，Mixin 会启动失败或漏掉 Flywheel路径。
- `getSynchronizedPressHeadProgress` 会通过静态 press-controller map、chamber 结构状态和 `getMechanicalPresses` 做有效性检查；客户端区块卸载、结构重建和 controller 清理必须和 map 生命周期一致，否则可能短暂使用过期 master。当前 helper 有 fallback，但 phase 跳变仍会产生视觉抖动。
- Press 的 `mode` 可能在不同成员上不同。同步 phase 来自 master，而 offset 乘数来自当前 Press；结构检测应保证这些 Press 使用同一 mode，否则“同相位但不同幅度”是否符合玩法需要必须明确。

公开 API 替代：没有直接的公共动画时钟替换点。可以注册自有 Press renderer/Visual，但 Create 的 Flywheel visual supplier由内部 Registrate 固定；也可以在 RenderLevelStageEvent 额外画一个 head，但无法阻止原 head，反而会重影。当前 Mixin 应保留，并和服务端 `PressingBehaviourSoundMixin` 一起做结构有效、master 变更、零速、mode 不同和 Flywheel 开关的回归测试。

综合评价：必要性高，侵入点单一，非 chamber Press 的原版计算基本完整，风险 P2。

### 10.13 `WorldSectionElementImplMixin`

目标是 Ponder `WorldSectionElementImpl.renderLayer` 中对 `MultiBufferSource.getBuffer(RenderType)` 的调用。当渲染类型是 `RenderType.translucent()` 且 buffer 实现 `SuperRenderTypeBuffer` 时，改用 `superBuffer.getLateBuffer(type)`，使 Ponder 的世界半透明区段在 late phase 绘制。

必要性：Ponder 1.0.82 的 `WorldSectionElementImpl` 直接用传入 buffer 的默认 phase；Ponder/Catnip 自身公开的 `SuperRenderTypeBuffer` 已提供 `getEarlyBuffer`、`getBuffer`、`getLateBuffer`，但没有给这个内置 WorldSectionElement 暴露“选择 phase”的配置。当前 Mixin 只把特定元素的 translucent 输出移动到 Catnip 已定义的 late buffer，避免结构半透明层与其他 translucent 几何的排序问题。

原版逻辑覆盖：非 translucent、不是 `SuperRenderTypeBuffer`、普通游戏 buffer 或非 Ponder调用都完整调用原 `getBuffer`；只有 Ponder 世界区段的 translucent 层改变 buffer phase。Ponder `PonderClient` 使用 `DefaultSuperRenderTypeBuffer` 并在同一帧 draw early/default/late，因而该注入确实有可观察效果。

公开 API 替代：`SuperRenderTypeBuffer` 是公开 API，但 Ponder 没有为已经构造的内置 `WorldSectionElementImpl` 提供替换其 `renderLayer` 的注册点。自定义 `PonderSceneElement` 可以自行选择 late buffer，却不能改变所有普通 world section。除非 Ponder 将 phase 作为 element/render API 暴露，否则当前 Mixin 是最小实现。

兼容性与映射：`RenderType.translucent()` 的身份比较依赖当前返回 canonical singleton；若未来改为等价但非同一实例，应改用明确的 render layer 判定。目标 owner 是第三方 Ponder，所以 `@Mixin(remap = false)` 用于类本身是合理的；注入方法和 nested `@At` 已显式 `remap = true`，因为描述符包含 Minecraft `MultiBufferSource`、`RenderType` 和 `GuiGraphics`，符合项目映射规则。Create/Ponder 版本变化仍可能改变 protected 方法描述符，当前配置为 required Mixin，启动时会硬失败。

综合评价：必要性中高，Ponder 专用范围窄，原版世界玩法无影响，渲染排序兼容风险 P2；当前不建议为“去 Mixin”而复制 Ponder 世界区段逻辑。

### 10.14 本批次结论与整改顺序

1. 立即修复 `CreeperRendererMixin` 的异常清理和嵌套作用域：用 `try/finally` 包住 PoseStack 与 swell 生命周期，保存状态按调用栈管理，不能让 RETURN 注入承担 finally 语义。
2. 立即修复 `SlimeMimicRenderLayer` 的 fallback overlay 控制流：在 `beginFallbackOverlay` 后用 `runWithoutPartInterception` 调用原模型，或提供一个只跳过当前拟态替换、仍允许正常 ModelPart 输出的独立模式；同时覆盖 flat cube、透明 PNG、隐身和带自定义层实体。
3. 收紧 `FluidTankRendererMixin` 的异常边界，尤其不要吞掉 Create 原始 `renderFluidBox` 的任意 `Throwable`；精确目标 descriptor，并测试经验流体、CEI 流体、资源包错误和 GPU/VertexConsumer 异常。
4. 将 `BeltSurfaceRenderScope` 改为可嵌套 token/栈，`FunnelRendererMixin` 和 `FlapStuffsMixin` 用成对的异常安全清理；canonical push 必须清理旧值，RETURN 不应依据当前值推断 HEAD 是否 push。
5. 把 `ClientLevelMixin` 的私有字段 Shadow 改用公开 `ClientLevel.getBlockStatePredictionHandler()`；保留 interface bridge，并测试预测序列和客户端回滚。
6. 将 Goggle overlay 的 Evoker 路径迁移到 Create `IProxyHoveringInformation`；Creeper 路径至少加 BlockState 快速过滤，随后评估为各结构块提供代理接口，以消除每帧遍历所有 chamber controller。
7. 为 `CameraMixin` 缓存当前传送器候选，保留 Camera 注入；为 Slime Mimic 的全局 ModelPart context 增加实体/renderer 归属，避免嵌套实体渲染串状态。
8. `BasinRendererMixin`、`FunnelVisualMixin`、`PressingBehaviourMixin` 和 `WorldSectionElementImplMixin` 当前没有发现可直接替代且更低风险的公开扩展点，可保留并在升级 Create/Ponder/Flywheel 时做目标描述符和视觉回归。

本批仍只追加审阅文档，没有修改模组源码，也没有运行编译或 `quickPlayClient`。结论依据当前工作区源码、活动版本和 `ref/1.21.1/Create`、`ref/1.21.1/Ponder` 的对应参考实现；其中 `ClientLevel.getBlockStatePredictionHandler` 的公开 accessor 还由当前构建生成映射核对。

### 10.15 `ItemApplicationCategoryMixin`

目标是注入 Create `ItemApplicationCategory.draw` 的 HEAD。当配方被识别为“传动杆安装猫箱”或“猫在传动杆上加面包”时，调用 `CuteCatOnShaftJeiRenderer` 绘制自定义场景并取消 Create 原本的方块预览。自定义 renderer 负责重绘 JEI 阴影、向下箭头、传动杆，以及猫、黄油、面包和绳子的局部模型。

必要性：Create 的 `ItemApplicationCategory` 已经注册并拥有 `ItemApplicationRecipe` 的分类实例，JEI 没有公开的“替换其他模组已注册分类”接口。`IRecipeCategoryDecorator` 只能在分类和槽位绘制之后追加内容，无法阻止原来的静态方块预览；`IRecipeSlotBuilder.setCustomRenderer` 只能在分类自己的 `setRecipe` 创建槽位时使用，Create 的分类没有给本模组机会修改这些槽位。因此若要求在 Create 的同一个配方分类中把静态方块预览换成带实体/局部模型的场景，当前 Mixin 有实际功能必要性。

原版逻辑覆盖：当前 `CuteCatOnShaftJeiRenderer.render` 复制了活动 Create 版本的阴影、箭头、位姿和 `GuiGameElement` 的基础变换，并在同一坐标系中添加模组模型。对非目标配方，HEAD 直接返回，Create 原 `draw` 完整执行；对目标配方，原分类的整个 `draw` 被取消，所以“完整覆盖”的责任落在自定义 renderer 上。当前已覆盖 Create 原方法在该版本实际绘制的公共元素，但这不是未来安全的覆盖方式：Create 若在 `draw` 中加入文字、背景色、额外动画或改变坐标，Mixin 不会自动继承这些变化。

当前有两个明确的行为风险：

- `CuteCatOnShaftJeiRenderer.getPreviewKind` 只检查 `recipe.getProcessedItem()` 是否匹配 Create Shaft，或者当前处理槽是否为模组的 `CUTE_CAT_ON_SHAFT`；它没有同时检查 required held item 和结果物品。第三方可以注册任意“以 Shaft 为输入”的 Item Application 配方，届时该配方会被误判为猫预览并隐藏原始预览；同理，任意以猫方块为输入的配方也可能被误判为加面包路径。应同时验证结果列表、required held ingredient 或配方 ID/配方语义，至少不能只凭输入方块判断。
- `render` 在 `CAT_TO_ENGINE` 路径已经绘制阴影、箭头和传动杆后，如果客户端 `Level` 或临时 `ButterCatEngineBlockEntity` 不可用，会返回 `false`。Mixin 随后不取消原方法，Create 会再次绘制同一批公共元素，产生重复绘制。失败应在绘制任何内容前返回，或让 renderer 返回“已部分绘制”与“应回退”之外的明确状态。

侵入性与兼容性：这是 Create JEI 分类级的可取消 HEAD 注入，影响面小于通用 JEI `RecipeSlot`，但控制流侵入较大。`@Pseudo` 只解决 Create JEI 类不存在时的可选依赖问题，不能防止 `draw` 签名或参数泛型发生变化；该注入没有 `require = 0`，目标方法变化会直接导致客户端 Mixin 应用失败。当前 `@Mixin(targets = ..., remap = false)` 配合注入描述符中的 Minecraft 类型使用 `remap = true` 是合理的，不能为了第三方 owner 而把整个注入改成 `remap = false`。

更低侵入的实现：若目标 Create 版本的调用点稳定，可以把分类级 HEAD/取消改成只包装 `GuiGameElement` 的具体渲染调用，保留 Create 的阴影、箭头、PoseStack 防护和未来公共逻辑；或者在 Create 的 `ItemApplicationCategory` 暴露渲染回调后改用回调。前者仍是 Mixin，只是从“复制整个方法”降为“替换一个 3D 预览调用”；JEI 当前公开 API 没有等价替代。若保留现状，应收紧目标配方判断、在失败前不产生部分绘制，并为普通 Shaft 配方、第三方 Shaft 应用配方、无客户端 Level 和资源重载建立回归用例。

综合评价：功能必要性中高，目标范围中等，普通 JEI 配方的原版逻辑保持完整，但目标配方的完整覆盖依赖复制 Create 逻辑；误匹配风险 P1/P2，版本字节码风险 P2。建议保留 Mixin 但优先改为调用点级 wrapper，并修复识别条件与失败回退。

### 10.16 `SpoutCategoryMixin`

目标是注入 Create `SpoutCategory.draw` 的 HEAD。当 Filling Recipe 的可滚动输出包含 `SQUID_PRINTER` 时，使用 `AnimatedSquidSpout` 绘制“鱿鱼打印机、鱿鱼、流体、墨水粒子和底座”场景，并取消 Create 原本的 Animated Spout 场景。

必要性：JEI 公开分类装饰器只能在原分类和槽位绘制之后追加，不能替换 Create 的 `AnimatedSpout`；也没有公开的 Create Spout Category renderer provider。对既有 `FillingRecipe`，要把 Create 的 Spout 动画改成模组机器动画，当前 Mixin 是直接可用的接入点。由于 `SpoutCategory` 本身是 Create 侧分类，不能通过本模组的 JEI `registerCategories` 再注册一个同类型分类来可靠替换原实例；那样会形成重复分类，且不保证 Create 的 recipe type/catalyst 选择到新分类。

原版逻辑覆盖：非 `SQUID_PRINTER` 输出的配方完全走 Create 原方法。目标配方中当前 Mixin 手动重绘了 Create 原方法的 `JEI_SHADOW`、`JEI_DOWN_ARROW` 和场景入口，随后用自定义动画代替 `AnimatedSpout.draw`；在活动参考版本中，177 像素宽背景对应的 `getBackground().getWidth() / 2 - 13 == 75`，所以当前硬编码位置与原方法一致。它没有改变配方槽位、流体 ingredient、结果 tooltip 或实际 Filling Recipe。

复制原逻辑带来的兼容性问题：

- 原 Create 方法使用 `getBackground().getWidth()` 计算动画位置，而 Mixin 固定使用 `75`。背景尺寸、分类布局或 Create 的坐标发生变化时，打印机动画会偏移；建议通过调用点 wrapper 把原计算得到的 offset 传入自定义 renderer，或者至少从活动分类背景取得尺寸。
- `AnimatedSquidSpout` 与 Create `AnimatedSpout` 一样直接使用 `fluids.get(0)`。合法 `SizedFluidIngredient` 通常有至少一个候选流体，但空流体或第三方构造的无效配方会在自定义路径抛 `IndexOutOfBoundsException`，而原分类也可能失败；自定义路径不应比原路径更早地取消整个分类，至少应在流体数组为空时放弃替换并让原方法处理。
- 判断条件是“结果列表中包含打印机”。这对当前模组配方是合理的，但若第三方配方把打印机作为多个随机结果之一，整张 Spout 预览都会被替换为打印机场景；应确认这是否是预期语义，或要求目标结果唯一匹配。
- 目标方法是 required injection，`@Pseudo` 只能保护 Create JEI 分类缺失，不能保护 `draw` 方法重构。项目的 JEI 依赖在 `neoforge.mods.toml` 中是从 `19.21.0.247` 开始的开放上限，而 Mixin 依赖 Create/JEI 的内部类和调用布局，开放上限与实际兼容承诺不一致。

更低侵入的实现：优先使用 `@WrapOperation` 包装 `AnimatedSpout.draw(GuiGraphics, int, int)` 的唯一调用，只在结果物品匹配时调用 `AnimatedSquidSpout`，让 Create 原方法继续负责阴影、箭头、PoseStack 和其他未来公共逻辑。若该调用点的参数捕获在目标版本不稳定，再保留当前 HEAD 方案并把原方法中不属于场景本身的逻辑集中到可复用 helper。JEI 公共 API 没有替换已注册 Create Category 的方案。

综合评价：功能必要性中高，注入目标较窄但当前是分类级取消，普通 Spout 配方影响低，复制逻辑和开放 JEI 版本范围构成 P2；空流体、背景尺寸变化和多结果 Filling Recipe 应纳入回归测试。

### 10.17 `JeiRecipeLayoutMixin`

目标是包装 JEI 内部 `mezz.jei.library.gui.recipes.RecipeLayout.drawRecipe` 对 `IRecipeSlotDrawable.draw` 的调用，在每个槽位真正绘制期间设置 `CapturedEntityBoxJeiRenderer` 的“当前槽位”和“当前是否悬停”上下文。当前版本使用 `draw(GuiGraphics, boolean)`；另一个 `draw(GuiGraphics)` redirect 是针对旧 JEI 方法形态的兼容分支，均设为 `require = 0`。

必要性：`IRecipeSlotDrawable.draw` 的 API 接收 hover 布尔值，但自定义 `drawIngredient` 注入点并不接收槽位对象或鼠标状态。JEI 的 `IRecipeCategoryDecorator` 只在 slot.draw 完成后执行，`IRecipeSlotView` 也没有全局当前 layout/hover 查询接口，故不能通过公开 API 在 ingredient renderer 内可靠得知“当前正在绘制哪个槽位、是否悬停”。如果只用 `JeiRecipeSlotMixin`，普通状态可以绘制实体，但悬停时无法切换回真实纸箱图标或选择当前槽位中的其他纸箱。当前 redirect 是把已有 hover 参数传递给 helper 的最小接入点。

公开 API 替代核对：JEI 19.21 的 `IRecipeSlotBuilder.setCustomRenderer` 只作用于创建槽位时，不能改 Create 现有分类；`IAdvancedRegistration.addRecipeCategoryDecorator` 只能追加绘制，不能包住槽位绘制；`IRecipeSlotDrawable` 是 `@ApiStatus.NonExtendable`，也没有 slot draw context callback。因此目前没有等价的公开 API。若仅需要本模组自己的 JEI 分类，可在这些分类的 `setRecipe` 中设置 custom renderer，届时可以不依赖全局 `RecipeSlot` Mixin；这不能覆盖 Create 分类和其他模组已经使用捕获纸箱的配方。

侵入性与风险：

- 目标是 JEI 所有配方 layout，每帧每个 slot 都执行 ThreadLocal `set/remove` 和一次 helper 调用，即使该 slot 不是捕获纸箱。大型 JEI 页面上会增加固定渲染开销；helper 可以先检查槽位中是否存在捕获纸箱，普通槽位直接走原 `draw`，再只为目标槽位建立上下文。
- `CapturedEntityBoxJeiRenderer` 的当前槽位和 hover 状态是单值 ThreadLocal。若 ingredient renderer、附加 widget 或其他模组在 slot.draw 内嵌套另一个 slot.draw，内层 `finally` 会直接 `remove` 外层值，外层剩余绘制会丢失上下文。应保存旧值并恢复，或使用栈/不可变 scope token，而不是无条件 remove。
- 当前新 API redirect 精确调用 `draw(GuiGraphics, boolean)`，能保留 JEI 原版 hover overlay；旧 API handler 根据 `getSlotUnderMouse` 重新计算 hover，并使用 `equals`，而 JEI 活动实现的原逻辑使用对象身份比较。为了完整镜像原版，应使用 `==` 语义，避免第三方 `IRecipeSlotDrawable` 重写 equals 后误判。
- `@Pseudo` 只处理 `RecipeLayout` 类不存在；两个 redirect 的 `require = 0` 会在 JEI 内部调用改变时静默失效，用户可能只看到纸箱恢复为普通物品图标而没有启动错误。该行为降低崩溃风险但隐藏功能不兼容，应该在开发日志或兼容诊断中报告。

兼容性：当前 `@Mixin` 的目标类是 JEI implementation package 而非 API，JEI `19.21.0.247` 之后没有上限。`@Redirect` 的 nested `@At` 使用 `remap = true` 是正确的，因为 descriptor 含有 Minecraft `GuiGraphics`；不能把它改为 `remap = false`。内部类、方法调用和 legacy overload 都属于版本耦合点，应把兼容版本上限写进依赖声明，或用独立 JEI Mixin plugin 按已验证版本加载。

综合评价：功能必要性高，目标类侵入高但单次逻辑修改小，普通 JEI 绘制的原版调用保持完整；性能开销和可嵌套上下文风险 P1/P2，JEI 内部 API 漂移风险 P1/P2。建议保留该 Mixin，同时优化普通 slot fast path、改用可嵌套 scope，并避免以 `require = 0` 静默掩盖失效。

### 10.18 `JeiRecipeSlotMixin`

目标是在 JEI 内部 `RecipeSlot.drawIngredient` 的 HEAD 检查当前 `ITypedIngredient` 是否是带实体的 `CapturedEntityBoxItem`。若能创建对应 LivingEntity，则绘制实体和纸箱标记并取消 JEI 原 `SafeIngredientUtil.render`；悬停时改为绘制实际箱子图标，使用户可以看到普通 item tooltip/状态。

必要性：这是跨所有 JEI 配方分类的 ItemStack 显示替换。NeoForge 没有一个可以只针对某个物品、且覆盖 JEI 所有现有 ItemStack slot 的客户端 ingredient render event。JEI 可以注册新 ingredient type 的默认 renderer，也可以在本方分类的 `IRecipeSlotBuilder` 上设置 custom renderer，但不能安全地替换 `VanillaTypes.ITEM_STACK` 的全局 renderer；更不能修改 Create 已完成构造的 recipe slots。若需求是让 Create、第三方及本模组所有配方中的捕获箱都显示实体，当前 Mixin 是必要的低层入口。

原版逻辑覆盖：非 ItemStack ingredient、非捕获箱、没有实体数据、实体创建失败时返回 `false`，JEI 原 `drawIngredient` 完整执行。自定义渲染成功时取消原 renderer，并由 helper 自己负责实体、badge、hover fallback 的绘制。实体创建后会关闭 Mob AI、静音并重置伤害/死亡状态，属于展示所需的本地临时实体，不会写回世界。

需要注意的行为变化：

- 取消原 `SafeIngredientUtil.render` 也会跳过该槽位原本配置的 `IIngredientRenderer`、renderer override、批量渲染路径和可能由第三方分类设置的特殊绘制。对捕获箱来说这是功能本意，但不是对所有 slot contract 的完整保留；若某个分类为该 ItemStack 设置了尺寸、shader、overlay 或特殊 z-order，Mixin 会强行覆盖。
- 自定义非悬停路径绘制实体和 badge，悬停路径只调用 `graphics.renderItem(displayedBox, x, y)`。这有意把鼠标悬停体验恢复为箱子，但仍应确认数量文本、ItemDecorator、深度层和 JEI highlight 是否与原 `RecipeSlot.draw` 的顺序一致。slot 的背景/overlay/hover highlight 仍由外层 `draw` 负责，当前顺序基本保持，但自定义实体可能改变 z 轴与其他 overlay 的关系。
- 目标是 JEI implementation 的私有方法 `drawIngredient`，注入未指定完整 descriptor 且默认 required=1；它比 Layout 的两个 redirect 更容易因 JEI 私有重构而启动失败。`@Pseudo` 只覆盖 JEI 不存在，不覆盖方法名、访问级别或参数变化。
- `CURRENT_SLOT_HOVERED.get()` 使用带 initial value 的 ThreadLocal。脱离 Layout redirect 的其他 JEI 调用会在渲染线程留下一个 false 值；通常只有一个线程槽，不是实体泄漏，但说明该上下文并没有严格绑定到 Layout 调用。

更低侵入的实现：本模组自有 JEI category 应优先在 `setRecipe` 对目标槽位使用 `setCustomRenderer`，可逐步减少全局拦截需求；对 Create/第三方既有分类，JEI 当前公开 API 不能做到等价覆盖。若将来愿意把捕获箱作为独立 JEI ingredient type 并让所有相关配方改用该类型，可以移除 `RecipeSlot` Mixin，但这会改变配方序列化、搜索、transfer handler 和现有配方数据，整体侵入性高于当前方案。保留 Mixin 时应精确 descriptor、明确兼容 JEI 上限，并考虑将失败策略设为 optional fallback 而不是让私有方法缺失直接阻断客户端启动。

综合评价：功能必要性高，目标类和调用频率侵入高，非目标 ItemStack 原版逻辑完整，捕获箱的第三方 renderer contract 有破坏性，内部 JEI 版本风险 P1/P2。建议保留作为跨分类兼容层，但优先迁移自有分类到公开 custom renderer，并补充自定义 renderer、overlay、tooltip、批量绘制和资源重载测试。

### 10.19 `LogisticalStockResponsePacketMixin`

目标是包装 Create `LogisticalStockResponsePacket.handle` 中的 `ClientLevel.getBlockEntity(BlockPos)`。当当前客户端菜单是 `WirelessStockKeeperRequestMenu`，且响应位置等于菜单的远程 `contentHolder` 位置时，不再从当前客户端世界查找方块实体，而直接把无线菜单创建的临时/远程 `StockTickerBlockEntity` 返回给 Create 原处理逻辑，使 `receiveStockPacket` 能接收跨维度库存响应。

必要性：Create 原 packet 的公开实现只做 `Minecraft.getInstance().level.getBlockEntity(pos)`，没有 packet handler callback、目标 BE provider 或跨维度路由接口。客户端当前世界没有远程 Stock Ticker 的真实 BlockEntity，单纯修改 `WirelessStockKeeperRequestMenu` 的 `stillValid` 或 screen 无法让 Create packet 找到它。可以自定义整套无线请求/响应 payload，或把假 BE 注入 ClientLevel，但前者要复制 Create stock ticker 网络协议，后者会污染客户端世界的 block entity map；当前单个调用 wrapper 是侵入性最低的方案。

原版逻辑覆盖：非无线菜单、无线菜单但位置不匹配、当前没有玩家或没有 `contentHolder` 时均调用原 `getBlockEntity`；因此普通 Create Stock Keeper 和普通世界方块实体不改变。匹配成功时只改变响应落点，仍由 Create `handle` 调用 `StockTickerBlockEntity.receiveStockPacket(items, lastPacket)`，没有重写库存聚合、排序或网络解码。

兼容性和 stale state 风险：

- 当前条件没有检查 `contentHolder.isRemoved()`、`contentHolder.getLevel()` 或菜单是否仍然 `stillValid`。客户端收到延迟响应时，即使远程目标已卸载、方块实体已移除或菜单已经处于关闭/失效过渡状态，仍可能把数据写入旧 holder。应至少排除 removed holder，并确认当前菜单/屏幕生命周期仍有效。
- packet 只有 `lastPacket`、位置和物品列表，没有请求序号或维度标识。无线菜单切换目标或快速关闭后重新打开同一位置时，旧响应可以落入当前仍匹配位置的菜单；这不是 wrapper 单独制造的协议问题，但它扩大了 stale response 对 fake holder 的可见影响。若业务上要求严格一致，应在无线自有请求协议中增加 session/request token，而不是继续依赖 Create 原 packet 的位置字段。
- `@WrapOperation` 的 `handle` 未指定方法 descriptor/ordinal；活动版本只有一个目标调用，但 Create 后续若在同一方法增加用于校验的 `getBlockEntity`，wrapper 可能覆盖多个调用。应固定当前 `handle(LocalPlayer)` descriptor，并限定 ordinal 或在 handler 中验证调用语义。

公开 API 替代：没有发现 Create 或 NeoForge 的公开等价路由点。可以改为无线自有 packet 并让无线菜单自己接收，但这属于协议重构，且要保持 Create 的 `BigItemStack` 分包和搜索刷新语义；在当前架构下保留 Mixin 合理。建议将路由条件集中在一个 `isCurrentWirelessTarget` helper，检查 holder 有效性和 menu ID/session，并在客户端切维度、关闭菜单、目标区块卸载及延迟多包场景下回归。

综合评价：必要性高，注入点窄，普通 Create 玩法逻辑完整，跨维度响应协议风险 P1/P2。该 Mixin 可以保留，但 stale holder 检查和目标调用收窄应优先处理。

### 10.20 `StockKeeperRequestScreenMixin`

目标是复用 Create `StockKeeperRequestScreen` 给无线终端使用。构造器 RETURN 对 `WirelessStockKeeperRequestScreen` 清空 Create 用来显示旁边 Keeper/Blaze 的两个 `WeakReference`；`containerTick` 包装 Create 的 `Player.closeContainer()` 调用，使无线 screen 不因远端没有本地 Keeper 或 Blaze 而自动关闭。所有其他 Create screen 逻辑仍由 superclass 执行。

必要性：本模组已经通过 NeoForge `RegisterMenuScreensEvent` 注册了 `WirelessStockKeeperRequestScreen`，它是 Create screen 的子类，而 Create 原 screen 的 `containerTick` 没有可覆写的“是否仍有 keeper”公开 hook。仅覆写 screen 的其他公开/受保护方法无法阻止 `super.containerTick()` 在末尾调用 `closeContainer()`；复制整个约 1800 行的 Stock Keeper UI 会失去 Create 后续修复并产生更大的逻辑漂移。因此在当前复用策略下，这个 Mixin 是比独立重写 screen 更低侵入的实现。

原版逻辑覆盖：构造器先完整执行 Create 初始化，包括 block entity、隐藏分类、搜索框、剪贴板模式和 keeper 查找，Mixin 只对无线子类把两项显示引用置空。`containerTick` 仍完整执行库存快照更新、搜索刷新、滚动、强制条目和 JEI 同步；只有 wireless screen 的“没有本地 keeper/blaze 就关闭”分支被抑制。非 wireless `StockKeeperRequestScreen` 完整调用原 `closeContainer`，所以普通 Create Stock Keeper 的关闭行为不变。

兼容性与原版影响：

- 将 `stockKeeper`、`blaze` 清空后，wireless screen 永远绕过这两个本地实体存活条件。若远程 holder 被移除、服务器断开或菜单同步已失效，screen 不能依靠该条件自行关闭；理论上应由 `WirelessStockKeeperRequestMenu.stillValid`、服务端 close packet 或 screen 的连接状态负责关闭。应验证目标区块卸载、切维度、断开服务器和远程方块破坏，避免留下可操作但无效的界面。
- 当前构造器直接继承 Create 初始化，依赖 `contentHolder` 非空且 fake holder 已经设置 level。`WirelessStockKeeperRequestMenu.createOnClient` 在客户端 Level 为空时返回 null；Mixin 并没有修复 Create 构造器对 null holder 的假设，因此“保留 screen 不关闭”不能被当作空 holder 的容错。
- `@WrapOperation` 只包装 `containerTick` 中匹配 `Player.closeContainer()` 的调用。当前参考源码该调用是 keeper 缺失分支的唯一关闭点，覆盖完整；若 Create 增加另一个与远程有效性相关的关闭路径，wireless screen 仍可能被关闭，或相反误拦截新语义。目标应使用完整方法 descriptor/ordinal，并在升级时重新检查所有 close call。
- `@Shadow` 的两个字段是 Create 自有字段，字段名和 owner 不是 Minecraft 映射成员；建议显式写 `@Shadow(remap = false)`，同时保留构造器/`Player.closeContainer` 等含 Minecraft 类型的注入目标按活动映射处理。当前字段只含 `WeakReference`，不会因为 descriptor 中没有 Minecraft 类型而需要 `remap = true`。

公开 API/低侵入路线：如果 Create 将 keeper 存活判断提取为 protected/public predicate，wireless screen 可以覆写 predicate 并删除 Mixin；当前 6.0.10 没有该扩展点。独立复制 screen 或通过反射清空字段都比当前 Mixin 更脆弱。中长期可以向 Create 提交一个“keeper requirement/close predicate”扩展点，或把 wireless screen 改成只复用共享的纯 UI helper；在此之前保留 Mixin，并将关闭条件迁移到无线菜单/连接生命周期的明确状态机中。

综合评价：必要性中高，目标类侵入中等，普通 Create screen 原版逻辑覆盖完整，wireless screen 的失效关闭语义由 Mixin 有意改变；远程生命周期风险 P1/P2，字段映射和内部方法风险 P2。该 Mixin 暂不建议删除，但应补充 holder/连接失效关闭机制并显式修正第三方字段 Shadow 的 remap 语义。

### 10.21 JEI/Stock Keeper 批次结论

本批六项中，当前没有可以直接用公开 API 完全替代的 Mixin；但可按侵入性分层整改：

1. `ItemApplicationCategoryMixin`、`SpoutCategoryMixin`：保留 Create 兼容入口，但优先从“HEAD 取消并复制整个分类 draw”改为包装单个 `GuiGameElement`/`AnimatedSpout.draw` 调用，让 Create 继续拥有公共背景、PoseStack 和未来逻辑；同时修正目标配方识别、失败回退和动态背景坐标。
2. `JeiRecipeLayoutMixin`、`JeiRecipeSlotMixin`：功能上需要跨 Create/第三方分类，但应把自有 JEI 分类迁移到 `setCustomRenderer`，减少全局 JEI 内部拦截；全局路径改为可嵌套 scope、普通 slot fast path，并给 JEI 依赖设置已验证的上限或版本门控。
3. `LogisticalStockResponsePacketMixin`：继续作为无线 fake holder 路由的最窄实现，增加 removed/level/session 检查并收窄 packet 调用目标；只有在改造无线协议后才考虑删除。
4. `StockKeeperRequestScreenMixin`：继续复用 Create UI，增加 wireless holder、连接和服务端菜单失效时的关闭策略；若未来 Create 提供 close predicate，再迁移到公开覆写点。

本批次追加内容仅修改了审阅文档，未修改模组源码，未运行编译或 `quickPlayClient`。依据包括活动版本 `Minecraft 1.21.1 / NeoForge 21.1.219 / Create 6.0.10-281 / JEI 19.21.0.247`，以及 `ref/1.21.1/Create`、`ref/1.21.1/JustEnoughItems` 中对应实现和 API；JEI 的本地参考与 `gradle.properties` 版本一致，依赖声明却未设置 JEI 上限，报告已将这一兼容性风险单独列出。

### 10.22 `SimBlockMovementChecksMixin`

目标是可选 Simulated 的 `dev.simulated_team.simulated.index.SimBlockMovementChecks.addAdditionalBlocks` 返回点。它把 `CBBeltChain.addConnectedSegments` 直接追加到 Simulated 的 assembly frontier，用于让 Slime Belt、Magma Belt 和 Power Belt 的前后段参与 Simulated 结构组装。

必要性：功能本身需要让 Simulated 的移动检查认识三种非标准 Belt；但当前 Mixin 没有必要。与当前工程参考最接近的 `ref/1.21.1/Simulated-Project/simulated/common/src/main/java/dev/simulated_team/simulated/index/SimBlockMovementChecks.java` 已公开 `registerAdditionalBlocks(AdditionalBlocks)`。该回调的参数正好提供 `BlockState`、`Level`、`BlockPos` 和已访问位置集合，功能上可以把 `CBBeltChain` 改成返回待追加位置的 helper，然后在 Simulated 初始化/兼容注册阶段注册回调，完全删除对 `addAdditionalBlocks` 字节码的注入。

兼容性与逻辑差异：

- 当前注入在 Simulated 已执行所有 `AdditionalBlocks` 后运行，并直接操作最终 `Queue`；公开注册接口使用 `addFirst` 登记回调，执行顺序可能不同。通常 frontier 顺序不应改变最终可移动集合，但多个扩展同时添加相邻位置时，遍历顺序可能影响冲突报告、装配失败位置或日志顺序。迁移时应做标准 Belt、三种 Biotech Belt、相邻胶黏块和多个扩展回调的结果对照。
- 当前方法的 descriptor 含 `BlockState`、`Level`、`BlockPos` 等映射类型，默认 `remap = true` 是正确方向；即使把它保留为 Mixin，也不能因为 owner 是第三方而关闭 remap。`@Pseudo` 只解决目标类可选，不解决方法名/签名变化。
- `ref/SOURCES.md` 明确说明本地 Simulated checkout 是 `main` 的近似分支快照，声明 1.3.0 但不是已发布 JAR 的精确提交。因此公开 API 的存在已被本地源码确认，但发布前仍需用实际支持的 Simulated JAR 做一次 API/二进制核对。
- `SableMixinPlugin` 当前把 Simulated 目标放在 Sable 配置中，并先检查 `sableApiAvailable`。如果 Simulated 可以单独运行，当前逻辑会在“有 Simulated、无 Sable”时错误地关闭这个兼容功能；这是不必要的可选依赖耦合。

公开 API 替代：可以，而且应优先迁移。由于 Simulated 是可选依赖，不能在常规初始化类中无条件直接引用其类型；应把注册器放在仅在 Simulated 存在时加载的 compat 类，或使用独立的 Simulated 可选配置/注册入口。不要为了删除 Mixin 而把 Simulated 变成必需依赖。若无法对实际发布 API 建立 compileOnly 依赖，反射注册仍比注入 Simulated 私有/内部 assembly 方法更容易隔离，但应把反射失败记录为一次兼容诊断。

原版/第三方影响：当前仅在 Simulated 存在时作用，不影响普通 Minecraft、Create 或没有 Simulated 的世界。综合评价：当前 Mixin 必要性低，目标方法侵入中等，可公开 API 完全替代，建议列为 P1 的结构性降风险项；迁移后保留“API 不存在时不加载”测试。

### 10.23 `UniversalJointEndpointBlockSableMixin`

目标为本模组的 `UniversalJointBlock` 和 `HalfShaftBlock`，通过同一个 Mixin 条件性实现 Sable 的 `BlockSubLevelAssemblyListener` 与 `BlockSubLevelLiftProvider`。

必要性：这是三个 Sable Mixin 中合理性最高的一项。两个方块必须让 Sable 识别其方向、空气阻力，并在子层级装配移动前后通知本模组方块实体更新持久地址；Sable 当前通过 `instanceof BlockSubLevelAssemblyListener` 和 `instanceof BlockSubLevelLiftProvider` 发现这些能力，没有等价的公开“为任意 Block 注册 adapter”回调。Sable 又是可选依赖，因此不能让自有方块源码直接 `implements` Sable 接口，否则 Sable 缺失时会在类加载阶段失败。条件 Mixin 是当前侵入性最低且符合 Sable public API 的方案。

逻辑完整性：

- `sable$getNormal` 使用方块的 `FACING`；两个目标都继承 Create 的定向动力方块，且该方向也是端点朝向支撑轴的方向，来源与方块自身轴定义一致。
- `sable$getParallelDragScalar` 和 `sable$getLiftScalar` 返回零，只开启 `sable$getDirectionlessDragScalar` 的端点空气阻力。它没有伪造升力，也没有修改普通世界的碰撞、动力或方块状态。
- `beforeMove` 在 Sable 保存源方块实体前调用 `createBiotech$beforeSubLevelMove`，递增端点移动代数、保存目标地址并标记源实体；`afterMove` 在目标方块实体加载 NBT 后调用对应收尾逻辑。这与本地 `ref/1.21.1/Sable/common/src/main/java/dev/ryanhcode/sable/api/SubLevelAssemblyHelper.java` 的 before/after 顺序相符。

风险与优化：

- `beforeMove` 已经修改了 `subLevelMoveSource`、`moveRevision` 和 `expectedOwnAddress`，但 Sable 的单个方块移动在 `try` 中失败时可能只记录异常而不调用 `afterMove`。如果发生保存、目标区块写入或方块实体恢复异常，源端点可能永久保留“正在移动”标记、预期地址或错误代数，进而影响掉落抑制、链接校验和后续同步。Sable public listener 没有失败回调，因此应在自有 endpoint 状态机中增加失败可恢复/下一 tick 校正，至少测试目标区块不可写、BE NBT 恢复失败和中途拆除三种路径。
- 两个目标共用一个实现类，任一方块的状态属性、方块实体类型或移动生命周期变化都会影响同一兼容 Mixin 的应用。可以拆成两个 Mixin，分别由插件检查目标类和接口完整性，减少失败连带范围；这不会改变功能，但会降低升级时的故障半径。
- `endpointAirDrag` 每个物理 tick、每个端点读取一次配置并转换为 float。开销较小，但配置允许值最高为 16，Sable 的方向无关阻力系数会直接作用于所有子层级端点；应在文档中说明这是有意的物理规则，并验证高值不会造成速度指数增长。Sable API 对 `parallel/lift/directionless` 的系数有稳定性约束，当前只启用 directionless 分支，数学上不受其 parallel/lift 下界约束，但仍需做长时间能量测试。
- `@Mixin` 目标是本模组自有类，除了 Sable 接口方法没有覆盖原方法；Sable 缺失时由可选配置门控，普通 Create 和原版玩法不受影响。接口本身是公开 API，低侵入替代不是另一个 API，而是把 Sable 变成必需依赖或增加官方 adapter 注册，这两者都比当前方案代价更高。

综合评价：必要性高（在可选依赖条件下），接口接入侵入低至中，普通玩法无影响，当前可保留，风险 P1/P2。优先修复移位失败后的状态恢复，并拆分双目标 Mixin；不建议改成复制 Sable assembly 逻辑。

### 10.24 `UniversalJointBlockEntitySableMixin`

目标是本模组 `UniversalJointBlockEntity`，条件性实现 Sable public interface `BlockEntitySubLevelActor`，并在每个 Sable physics substep 中为跨子层级的 Universal Joint 施加弹簧/阻尼冲量。

必要性：Sable 的 `LevelPlot` 将方块实体以 `instanceof BlockEntitySubLevelActor` 收集，`ServerSubLevel.prePhysicsTick` 再调用 `sable$physicsTick`；当前没有针对一个任意 BlockEntity 的等价公开注册表。直接让方块实体实现接口会使可选 Sable 变成硬链接，因此 Mixin 是当前最小的可选兼容入口。用 Sable 全局 pre-physics event 迭代所有 Universal Joint 虽然理论上可行，但需要自行发现子层级、获取 handle、处理卸载和去重，扫描范围更大，侵入性和性能风险都更高。

当前控制流与逻辑同源性：

- 先检查服务端、有限正时间步、有效当前刚体、端点预期地址、双向验证链接和当前子层级 UUID，避免过期/错误地址参与物理。
- 通过 `getLoadedLinkedJoint`、双方 `references` 和 `shouldOwnElasticLink` 做双向确认及单边 owner 选择；同一子层级不施加跨刚体弹簧，外部世界端点按静态锚点处理，两个不同子层级则对双方 handle 施加相反冲量。
- 位移使用 `SubLevelCompat.toWorld`，速度使用 Sable companion 的世界速度，方向再转换回各自子层级的 local normal；这与 Sable 的“物理 handle 接收 plot-local impulse”约定相符。
- 弹簧张力来自 Universal Joint 自身的 `strainStartDistance`、`disconnectDistance` 和 `peakTension` 配置，使用 smoothstep、相对径向速度、有效法向逆质量和 `maxImpulse` 限制。距离超过断裂范围只设置 `pendingOverstretchBreak`，真正的断裂仍在方块实体正常 tick 中执行；没有绕过 Create 的断裂、掉落或动力重建逻辑。
- 两个 `ForceTotal` 按当前/peer 分开复用，并通过 Sable public `applyForcesAndReset` 清空本步数据。只要 Sable `ForceTotal` 的 reset 语义保持不变，不会把前一步冲量累计到下一步。

必须处理的兼容与物理风险：

- `MassData.getCenterOfMass()` 在 Sable public API 上明确标为 nullable，而 `MassData.isInvalid()` 只检查质量，不保证中心质量存在。当前 `createBiotech$inverseNormalMass` 只判断 `mass == null || mass.isInvalid()`，随后调用 `getInverseNormalMass`，而该默认实现会直接对 `getCenterOfMass()` 解引用。质量数据正在合并、空子层级或自定义质量实现返回空中心时，物理 tick 可能抛异常并被 Sable 物理管线升级为服务器错误。应先检查 COM 非空且坐标有限，并对 inverse normal mass 做有限值/非负校验；这是 P1。
- 当前只检查 `currentHandle` 有效，没有显式检查 `currentSubLevel.isRemoved()`、`peerSpace.isRemoved()` 或 peer handle 获取与物理系统状态的一致性。Sable 主循环通常会跳过已删除的当前子层级，但 peer 子层级可能在查找后被移除；在 `createBiotech$applyImpulse` 前应再次确认 peer space/handle 有效，失败时不要只对当前刚体施加单边冲量。
- `SubLevelCompat.findSubLevel` 通过反射查找 `SubLevelContainer.getContainer(Level)`/`getSubLevel(UUID)`，每个 owner endpoint 的每个 physics substep 都可能执行一次。它把可选 API 隔离得很好，但在已经确认 Sable 存在的 Mixin 中仍有重复反射和失败静默问题。可以在 Sable compat 层缓存 UUID 到当前 tick 的 sublevel/handle，或在这个兼容类中直接使用已验证的 Sable public `SubLevelContainer` API；不能让缓存跨卸载生命周期长期持有已删除对象。
- `timeStep` 的单位在 `BlockEntitySubLevelActor` 和 `SubLevelPhysicsSystem` 中是秒，且 Sable 可能每游戏 tick 执行多个 substep。当前使用 `timeStep` 计算冲量，方向上是正确的；但 spring/damper 离散公式、速度为零、接近应变起点、急速分离、急速回缩和 substeps 改变时的能量变化没有代码级证明，必须做数值回归。至少要验证相同配置下 1/2/4/8 substeps 的断裂时间、最大距离、最终速度和总能量趋势。
- `springForce`、`damping`、`maxImpulse` 来自可配置值。ModConfig 的范围能限制常规值，但运行期仍应在乘法、分母和 `Math.copySign` 前确认各输入有限；异常值不能让 physics callback 抛出。

公开 API 替代：没有同等低侵入的替代。Sable public actor 接口本身已经是正确扩展点，Mixin 只负责把它附加到一个可选依赖下的自有方块实体。若 Sable 将来提供 BlockEntity actor provider/registration API，可迁移到注册回调；在此之前，不应改为覆盖 Sable 物理管线或复制整个 `ServerSubLevel.prePhysicsTick`。

原版影响：Sable 未安装时此 Mixin 不应用；Sable 安装但 Universal Joint 不在 Sable sublevel 时，入口的 owner/space 检查使其不施加冲量。它不会覆盖 Universal Joint 原有 tick、Kinetic 网络或断裂逻辑，影响仅限“使用 Sable 物理子层级的 Universal Joint”这一新增兼容场景。综合评价：必要性高，目标范围窄但每次 physics substep 执行，风险 P1（质量中心/卸载/数值稳定性）和 P2（API 漂移/性能）。

### 10.25 `SableMixinPlugin`

这不是目标 Mixin，而是 `create_biotech_sable.mixins.json` 的 `IMixinConfigPlugin`，决定三个兼容 Mixin 是否应用，因此必须作为兼容边界单独审阅。

优点：配置本身 `required: false`；插件只用 class resource 检查，不在可选依赖缺失时直接 `Class.forName`；Sable 的接口、物理类、`SubLevelContainer` 和 `ServerSubLevel` 都存在时才放行 Universal Joint 兼容层，能避免最常见的 `NoClassDefFoundError`。

主要问题：

- `shouldApplyMixin` 先判断 `sableApiAvailable`，再判断 Simulated Mixin。因此即使 `SimBlockMovementChecks` 存在、Sable 不存在，Simulated Belt assembly 兼容也会被关闭。除非项目明确规定 Simulated 必须始终和 Sable 一起安装，否则这属于错误依赖门控。应让 Simulated Mixin 独立返回 `simulatedMovementChecksAvailable`，更稳妥的是拆成独立的 Simulated compat 配置。
- 只检查 class resource，不检查方法/字段/接口签名。当前 `sable_version_range=[1.1.3,3.0.0)`，而源码实际使用了 `BlockEntitySubLevelActor.sable$physicsTick`、assembly listener 的 before/after、lift provider 的四个方法、`ForceTotal.applyImpulseAtPoint`、`RigidBodyHandle.of/applyForcesAndReset/isValid`、`MassData.getInverseNormalMass/isInvalid` 和 `SubLevelContainer.getSubLevel`。其中任一个在旧/新 Sable 中改名、改 descriptor 或改为抽象层级变化，class resource 仍然存在，插件却会放行，最终在 Mixin 应用或首次调用时失败。应使用已验证的版本范围，或在不初始化游戏逻辑的前提下做明确 API probe/反射签名检查，并在失败时关闭整组兼容。
- `SimBlockMovementChecksMixin` 的目标类检查与 Sable API 检查混在同一个 config，扩大了可选兼容的故障面；一个 API 族的版本不匹配会让另一个本可工作的 API 族也被禁用。
- `@Mixin({ UniversalJointBlock.class, HalfShaftBlock.class })` 的两个目标共用一份实现，插件没有检查这两个目标类的可应用性，也没有为接口方法失败提供单目标降级。拆分 Mixin/config 或至少为两类目标做版本化 smoke test，可降低连带失败。

建议的门控顺序是：先按 Mixin 类名判断 Simulated 或 Sable，再检查各自完整 API；不要用一个总布尔值覆盖两个可选依赖。对重要兼容失败，应在开发日志中说明“禁用了哪一项能力”，避免用户只看到 Universal Joint 在 Sable 中没有物理效果却没有诊断信息。

综合评价：插件没有直接改变原版玩法，但它决定可选依赖下的启动稳定性，风险 P1/P2。应在发布前完成 API 签名门控、Simulated/Sable 解耦和三种组合测试：无两者、仅 Simulated、仅 Sable、两者同时存在（实际应扩展为四种组合）。

## 11. 全量清单、风险排序与最终结论

### 11.1 清单口径

配置层实际列出 73 个 Mixin 目标：主配置 common 46 个、client 23 个，Allay 配置 1 个，Sable 配置 3 个。`SableMixinPlugin` 是配置插件，不计入 Mixin 目标；`SlimeChainData` 是被 `LaunchedItemForBeltMixin` 注入对象实现的桥接接口，也不单独计数。下表用于逐项索引；前文对应批次已经给出每个目标的控制流、公开 API 替代和原逻辑覆盖说明。

### 11.2 73 项逐项结论

| 配置 | Mixin | 目标/作用摘要 | 公开 API 或低侵入路线 | 结论/风险 |
| --- | --- | --- | --- | --- |
| common | `AbstractContraptionEntityBufferPadMixin` | Contraption 缓冲垫碰撞/边界补偿 | 当前无等价 Create 回调；缓存“是否含缓冲垫” | 保留；普通 Contraption 扫描性能 P1/P2 |
| common | `AbstractVillagerAccessor` | 读写 AbstractVillager 报价字段 | 暂无实例报价 setter/事件 | 保留窄 accessor；P2 |
| common | `AbstractVillagerSlimeMimicTradesMixin` | 拟态村民交易载入/恢复 | 需要报价策略 API 或存档事件 | 保留；必须保留普通报价分支；P1/P2 |
| common | `AbstractHorizontalFunnelBlockMixin` | 水平 Funnel 方向/状态判定扩展 | 当前无 Surface provider API | 保留条件分支；P2 |
| common | `AssemblyOperatorBlockItemMixin` | 允许 Biotech Belt 被装配操作器识别 | 需要 `Predicate<BlockState>` 注册 API | 保留；P2/P3 |
| common | `BasinBlockEntityMixin` | 捕获实体输出路由与处理 | 当前无 Basin output policy | 保留窄包装；输出原子性 P1 |
| common | `BasinInventoryMixin` | 阻止捕获箱/控制物品绕过 Basin 边界 | 当前无 Basin inventory policy | 保留；内部/Capability 双路径 P1 |
| common | `BasinBlockMixin` | Basin 交互/掉落路径保护 | 无专用 Basin 清空策略事件 | 保留条件取消；P2 |
| common | `BasinBlockSlimeBeltOutputMixin` | Basin 向 Slime Belt 输出 | 无 `canOutputTo` 注册策略 | 保留；输出方向 P1/P2 |
| common | `BlockEntityConfigurationPacketMixin` | 配置包的子层级/空间校验 | 需统一 Create 配置包 resolver | 保留；网络/空间安全 P1 |
| common | `BlockBreakingMovementBehaviourMixin` | 移动结构破坏兼容 | 当前无完整行为策略注册点 | 保留并核对失败掉落；P2 |
| common | `BrassTunnelBlockEntityMixin` | Tunnel 对非标准 Belt 的 handler 适配 | 需要 Tunnel output provider | 保留调用包装；内部视图 P1/P2 |
| common | `ContraptionMixin` | Contraption 状态/实体空间适配 | 需 Create contraption lifecycle API | 保留；核心结构行为 P1/P2 |
| common | `DeployerMovementBehaviourMixin` | Deployer 伤害/捕获实体上下文 | 需 Create deployer action callback | 保留；覆盖范围 P1/P2 |
| common | `BeltTunnelBlockMixin` | 非标准 Belt 的 Tunnel 状态与生存 | 需 Tunnel state provider | 保留；状态机复制 P1/P2 |
| common | `BeltTunnelBlockEntityCapabilityMixin` | 清理 Tunnel 惰性 capability 缓存 | 当前无公开 cache reset | 保留窄 accessor；P2 |
| common | `BeltTunnelInteractionHandlerMixin` | Tunnel 与自有 Belt 的插入/抽取 | 需 Tunnel output handler API | 保留调用包装；P2 |
| common | `LaunchedItemForBeltMixin` | 传递 Belt 链/滑轮偏移数据 | 自有接口已是低侵入桥接 | 保留；校验数组生命周期；P2 |
| common | `SchematicannonBlockEntityMixin` | 蓝图炮发射物的链数据传递 | 需 Create 发射上下文 API | 保留；普通炮逻辑应完整委托；P2 |
| common | `BeltMovementHandlerMixin` | 自有 Belt 运动/实体运输适配 | 需 Create Belt movement provider | 保留；高频路径 P2 |
| common | `BeltFunnelBlockMixin` | Surface Funnel 状态/运输扩展 | 需 Create Surface-aware Funnel API | 保留；原状态机覆盖 P1/P2 |
| common | `BeltFunnelBlockStateMixin` | 新增 attachment surface 状态属性 | 无动态属性注册 API | 保留；状态迁移 P1 |
| common | `BeltFunnelShapeMixin` | Surface Funnel Shape/碰撞 | 无 Shape provider | 保留；六向 Shape P2/P3 |
| common | `FunnelBlockMixin` | Funnel 内实体捕获追加 | 无实体进入后处理事件 | 保留追加式；P2 |
| common | `FunnelBlockEntityMixin` | 捕获实体后的 Funnel 行为桥接 | 无专用 callback | 保留；初始化时序 P2 |
| common | `FunnelItemMixin` | Surface Funnel 放置状态 | 无放置状态 resolver | 保留条件取消；P2 |
| common | `FluidTankBlockEntityMixin` | Fluid Tank 对经验/特殊流体处理 | 需 Create fluid display/behavior API | 保留；必须逐分支委托原逻辑；P2 |
| common | `MagmaBeltFunnelBlockMixin` | Magma Belt 的 Shape/扳手兼容 | 可部分用 DirectBeltInputBehaviour | 保留；静态 Shape 仍需注入；P1/P2 |
| common | `LivingEntitySlimeMimicHurtSoundMixin` | 拟态实体受伤声音 | 无通用 renderer/entity sound policy | 保留追加；P2 |
| common | `LivingEntitySlimeMimicMixin` | 任意 LivingEntity 的拟态状态/行为 | 专用实体 renderer 无法覆盖任意拟态 | 保留；状态同步 P1/P2 |
| common | `HauntingTypeSlimeMimicMixin` | Create Haunting 对拟态实体分流 | 可注册 wrapper `FanProcessingType`，但会改变 registry identity | 当前保留或迁移 wrapper；原副作用完整性 P1/P2 |
| common | `NozzleBlockMixin` | Nozzle 对自有 Belt/子层级处理 | Sable/创建侧暂无完整 nozzle provider | 保留；普通 Nozzle 分支 P2 |
| common | `NetherPortalBlockMixin` | 传送门方块作为流体源/状态扩展 | 需公开 Portal fluid provider | 保留；P2 |
| common | `PortalForcerMixin` | 传送门创建/位置适配 | 无等价 NeoForge portal strategy | 保留；世界生成边界 P1/P2 |
| common | `MobAccessor` | 清除 Mob 持久化标记 | 无公开 clear setter | 保留窄 accessor；P2 |
| common | `PackageItemCardboardBoxMixin` | 包装箱捕获实体数据/显示适配 | 需 Create package payload/render hook | 保留；数据兼容 P2 |
| common | `PressingBehaviourSoundMixin` | Chamber Press 声音与 master 同步 | 无公共 Press sound clock hook | 保留；P2 |
| common | `PowerBeltWalkAnimationMixin` | 动力带表面运动动画增量 | 需 Belt movement animation callback | 保留 TAIL 增量；P2 |
| common | `WalkAnimationStateAccessor` | 读写原版行走动画状态 | 未来可用动画事件替代 | 保留窄 accessor；P2 |
| common | `MixinSuperGlueSelectionHelper` | Smart Glue 选择过滤/范围 | 需 Create selection predicate API | 保留；P2 |
| common | `SawBlockEntityMixin` | Saw 对自有处理/实体兼容 | 需 Create saw processing callback | 保留并核对配方/掉落；P2 |
| common | `ServerGamePacketListenerAccessor` | 读取方块预测确认序列 | 需 NeoForge acknowledgement API | 保留；网络协议 P1 |
| common | `SmartBlockEntityLegacyRefreshMixin` | 旧存档/刷新状态兼容 | 需 SmartBlockEntity migration hook | 保留；P2/P3 |
| common | `SpawnEggItemMixin` | Spawn Egg 对拟态/自定义实体生成 | 需 NeoForge spawn hook 覆盖同一返回值 | 保留条件分支；P2 |
| common | `StockKeeperRequestMenuAccessor` | 修改无线 Stock Keeper 菜单锁定/权限状态 | 需 Create menu factory/权限回调 | 保留；字段耦合 P2 |
| common | `VillagerSlimeMimicTradesMixin` | Villager 交易生成/追加拟态交易 | 需公开交易策略事件 | 保留；普通交易逻辑 P1/P2 |
| client | `BasinRendererMixin` | Basin 捕获输出/流体可视化 | 需 Basin renderer provider | 保留；P2 |
| client | `CameraMixin` | 子层级/传送器相机位置 | 需公开 camera transform event | 保留并缓存候选；P2 |
| client | `ClientLevelMixin` | 方块预测 handler/空间桥接 | 可用 `ClientLevel.getBlockStatePredictionHandler()` 删除私有 Shadow | 建议迁移公开 API；P2 |
| client | `CreeperAccessor` | 读写 Creeper swell 状态 | 无公开临时 swell setter | 保留窄 accessor；需 finally；P1 |
| client | `CreeperRendererMixin` | 高压苦力怕临时渲染脉动 | 无通用 renderer phase hook 可替换状态时钟 | 保留；HEAD/RETURN 清理异常不安全，P1 |
| client | `FlapStuffsMixin` | Funnel flap 动画状态同步 | 需 Create flap state provider | 保留；ThreadLocal 嵌套/异常 P2 |
| client | `FluidTankRendererMixin` | 特殊流体/经验渲染 | 需 FluidTank renderer provider | 保留；吞 `Throwable` P1 |
| client | `FunnelRendererMixin` | 自有 Funnel Surface 渲染 | 需 Funnel renderer provider | 保留；scope 清理 P2 |
| client | `FunnelVisualMixin` | Flywheel Funnel visual 状态 | 需 Visual factory/provider | 保留；P2 |
| client | `GoggleOverlayRendererMixin` | Chamber/拟态 Goggle 信息 | 可将 Evoker 分支迁移 `IProxyHoveringInformation` | 部分可迁移；Creeper 查询成本 P1/P2 |
| client | `ItemApplicationCategoryMixin` | 替换 Create JEI Item Application 预览 | 无替换既有分类 API；可 WrapOperation | 保留但改调用点级包装；误识别 P1/P2 |
| client | `JeiRecipeLayoutMixin` | 向 ingredient renderer 传递槽位/hover context | 自有分类用 `setCustomRenderer`，全局无等价 API | 全局保留但改嵌套 scope；JEI 内部漂移 P1/P2 |
| client | `JeiRecipeSlotMixin` | 捕获箱 ItemStack renderer 注入 | JEI 无全局 ItemStack 替换事件 | 保留兼容层；高频内部类 P1/P2 |
| client | `LivingEntityRendererMixin` | 任意实体拟态 renderer context | 专用 renderer 不能覆盖任意实体 | 保留；类级渲染侵入 P1/P2 |
| client | `LogisticalStockResponsePacketMixin` | 无线 Stock Keeper 响应路由 | 需重构为自有 packet 协议 | 暂保留；stale holder/session P1/P2 |
| client | `PressingBehaviourMixin` | Chamber Press 动画 phase 同步 | 无公共 Press animation clock | 保留单点注入；P2 |
| client | `SpoutCategoryMixin` | 替换 Create JEI Spout 场景 | 无既有分类替换 API；可 Wrap AnimatedSpout | 保留但改调用点包装；P2 |
| client | `StockKeeperRequestScreenMixin` | 无线 Stock Keeper screen 生命周期 | 需 Create close predicate | 保留；远程失效 P1/P2 |
| client | `LevelRendererAccessor` | 读取渲染 ticks | 可用 AnimationTickHolder/公开帧 API | 可迁移；P3 |
| client | `MixinSuperGlueSelectionHandler` | 客户端 Smart Glue 选取/绘制 | 需 Create selection handler callback | 保留；P2 |
| client | `ModelPartAccessor` | 读取 cubes/children 模型结构 | 无任意 ModelPart 遍历/UV API | 保留低层 accessor；P1/P2 |
| client | `ModelPartRenderMixin` | 递归替换拟态 ModelPart 顶点输出 | 无等价公开渲染 API | 保留；fallback context 缺陷 P1 |
| client | `WorldSectionElementImplMixin` | Ponder translucent late buffer | Ponder 无内置 world section phase provider | 保留；P2 |
| Allay | `PackagerBlockEntityMixin` | Packager 尾部唤醒 Allay Port | 无 Create frog wake callback | 保留 TAIL；Create 版本漂移 P2 |
| Sable | `SimBlockMovementChecksMixin` | Simulated assembly 追加 Biotech Belt 段 | `SimBlockMovementChecks.registerAdditionalBlocks` | 建议删除并迁移公开 API；P1 |
| Sable | `UniversalJointEndpointBlockSableMixin` | 给两个端点方块附加 Sable listener/lift 接口 | 当前无 Block adapter 注册 API | 保留；before/after 失败恢复 P1/P2 |
| Sable | `UniversalJointBlockEntitySableMixin` | Sable physics substep 弹簧/阻尼冲量 | 当前无 actor provider 注册 API | 保留；COM/卸载/数值稳定 P1 |

### 11.3 按风险的整改优先级

**P1：应先处理**

1. 修复 `CreeperRendererMixin` 的状态生命周期：PoseStack、ThreadLocal、swell 和任何临时渲染标志必须在 `try/finally` 中按嵌套调用栈恢复，不能依赖 `RETURN` 作为 finally。
2. 收紧 `FluidTankRendererMixin` 的异常边界，不能用宽泛 `Throwable` 吞掉 Create/Catnip/驱动渲染错误；失败时只回退本模组特殊流体路径。
3. 修复 `ModelPartRenderMixin`/`SlimeMimicRenderLayer` 的 fallback context：进入跳过 ModelPart 的上下文后，fallback 模型绘制必须明确退出拦截，否则可能绘制零顶点。
4. 为 `BasinBlockEntityMixin`/`BasinInventoryMixin` 验证捕获物品的输出原子性、外部 Capability、Create 内部直接槽位和异常回滚，避免“实体已标记但输出未提交”或控制物品可被抽走。
5. 为 `LogisticalStockResponsePacketMixin` 增加 holder、menu/session、目标维度/连接和 removed 状态校验；延迟响应不能写入已关闭或已换目标的无线界面。
6. 修复 `UniversalJointBlockEntitySableMixin` 的 nullable center-of-mass、removed peer 和有限配置输入；增加 physics substep 数值回归。
7. 修复 `SableMixinPlugin` 的 Simulated/Sable 门控耦合，并对实际支持的 Sable 版本做 API 签名验证；否则可选依赖会把本应降级的兼容变成启动或首 tick 错误。
8. 将 JEI 分类 HEAD 取消改为 `WrapOperation`/调用点级替换，修正 Item Application 和 Spout 的目标配方识别、空流体、动态背景和失败回退。

**P2：完成核心稳定性后处理**

- 将所有全局 ThreadLocal scope（`FlapStuffsMixin`、`FunnelRendererMixin`、JEI context、ModelPart context 等）改为可嵌套 token/栈，并在异常、递归渲染和多线程误用时恢复旧值。
- 将 `ClientLevelMixin` 的私有 prediction handler Shadow 改为 `ClientLevel.getBlockStatePredictionHandler()`；核对 accessor 的 mapped descriptor/refmap。
- 将 Goggle overlay 的 Evoker 路径迁移到 Create `IProxyHoveringInformation`，为 Creeper 结构提供更窄的代理/索引；避免每帧扫描全部 controller。
- 给 JEI 依赖设置已验证的上限或版本门控。当前 `[19.21.0.247,)` 与使用 JEI 内部 `RecipeLayout`/`RecipeSlot` 的事实不一致；`require = 0` 只能作为可诊断降级，不能替代兼容策略。
- 对所有取消原方法的 Mixin 建立“活动 Create 源码逐行 diff”检查，重点覆盖 Basin 输出、Belt Funnel 状态、Tunnel 状态、Haunting、Villager 交易、Item Application 和 Spout 分类。
- 拆分 `UniversalJointEndpointBlockSableMixin` 的两个目标，减少任一方块升级造成的连带失败；给 `beforeMove`/`afterMove` 建立移动失败恢复测试。

**P3：可选清理**

- 用公开动画 tick API 替换 `LevelRendererAccessor`，并评估是否可通过动力带/渲染事件替代 `WalkAnimationStateAccessor`。
- 对只读字段 accessor 建立映射存在性检查，但不要为此复制目标类逻辑。
- 继续保留 Ponder `WorldSectionElementImplMixin`、Press 动画单点 Mixin 等没有等价扩展点的窄注入，升级依赖时重查 descriptor。

### 11.4 覆写完整性审计结论

当前 Mixin 集合大多数是 accessor、TAIL 追加或单调用包装，普通原版实体、普通 Create Funnel/Belt、非目标配方和未安装可选依赖的路径大体保持原逻辑。真正需要“完整覆盖原版方法”的位置集中在：Basin 输出/库存边界、Surface Funnel 状态与放置、Tunnel 状态、Haunting 拟态分流、Villager 交易恢复、JEI Item Application/Spout 分类和部分客户端渲染回退。

这些位置不能只以“目标配方/目标方块分支能工作”作为完整性证明。审阅标准应逐项保留原方法的权限检查、客户端/服务端分支、红石/过滤条件、冷却、失败返回、事件/同步、掉落、槽位/流体输出和未来可扩展调用；对目标分支必须在取消前完成所有不相关原逻辑，或改为只包装 Create 的一个调用点。当前已经发现的明确缺陷是 Creeper 渲染异常清理、Slime Mimic fallback 拦截和 Fluid Tank 过宽异常捕获；它们都不是“理论风险”，应按发布阻断项处理。

### 11.5 验证矩阵

| 层级 | 必测内容 | 通过标准 |
| --- | --- | --- |
| 编译/映射 | `./gradlew compileJava`；检查 `build` 中生成的 `create_biotech.refmap.json` | 所有含 Minecraft/Mojang descriptor 的注入都生成正确映射；没有用 `remap = false` 遮蔽映射错误 |
| 启动组合 | 无 Sable、仅 Simulated、仅 Sable、Sable+Simulated；无 JEI/有 JEI；Allay 代码路径 | 可选依赖缺失时只关闭对应功能；没有 `NoClassDefFoundError`、Mixin apply error 或首 tick 错误 |
| 原版回归 | 普通 Basin、Funnel、Belt、Tunnel、Villager、Creeper、Spawn Egg、Portal、Fluid Tank、Saw、Deployer | 非目标状态逐项执行原版逻辑，输出、掉落、声音、红石、权限和同步不变 |
| Create 兼容 | 标准 Create Belt 与三种 Biotech Belt 混用；普通/Brass Tunnel；普通/Chamber Press；Flywheel 开关 | 标准 Create 分支不被自有分支截断；状态、Shape、能力缓存和动画一致 |
| 存档/网络 | 旧 Villager/Basin/Universal Joint NBT；客户端预测；Stock Keeper 延迟/重复/关闭/换目标响应 | 旧数据可恢复；过期包不污染新菜单；预测序列不倒退 |
| 客户端异常 | 资源包纹理、透明模型、隐身实体、嵌套 renderer、资源重载、GPU/VertexConsumer 异常 | PoseStack、ThreadLocal、模型拦截和渲染状态始终恢复；错误不被无条件吞掉 |
| Sable 物理 | 外锚点、单/双子层级、同子层级、子层级卸载、质量合并中、不同 substeps、断裂边界 | 冲量方向相反且有限；无 COM 空值崩溃；能量/断裂结果随 substeps 收敛 |
| JEI | 目标/非目标配方、第三方同输入配方、空流体、多结果、hover、tooltip、资源重载 | 目标场景替换正确；非目标分类完全走 Create；失败回退不重复绘制 |

本报告至此覆盖配置中列出的全部 73 个 Mixin。文档审阅期间只修改了 `docs/optimization/10-mixin-full-review.zh-CN.md`，没有修改模组源码；因此本轮没有运行编译或 `quickPlayClient`。完成上述 P1 修复后，按项目约定先运行最小 `compileJava` 并检查 refmap，再在有明确退出/清理机制的前提下运行 `quickPlayClient`。
