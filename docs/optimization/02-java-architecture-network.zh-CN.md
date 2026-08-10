# 02. Java 架构、网络与客户端边界审计

## 结论摘要

主体功能按特性分包，注册、客户端入口和网络通道也有清晰的基本框架；问题主要出现在项目扩张后的边界退化：Allay 子系统与主体双向依赖，多个来源模块使用不同注册风格，少数核心类同时承担服务端状态机、客户端表现、持久化、网络和 UI 职责，客户端到服务端的数据包校验又不一致。

最先处理的应是网络信任边界和服务器生命周期泄漏，然后再拆模块与大类。大规模重构不应一次完成，建议先提取可测试的纯逻辑对象和 facade，保持注册名、NBT 格式与网络协议兼容。

## 1. 架构基线

- Java：425 个文件，约 62,369 个物理行。
- 主体包：`com.nobodiiiii.createbiotech.*`。
- Allay 包：`com.yision.allay.*`，与主体共同打包、共同使用 `create_biotech` mod id 和同一个网络通道/refmap。
- 两个包族之间存在 43 条显式跨包 import：26 条主体到 Allay，17 条 Allay 回到主体。
- 客户端注册集中于约 402 行的 `CreateBiotechClient`，但大量客户端实现仍散布在各特性包中。
- 105 个 Java 文件导入 Minecraft/Flywheel 渲染类，其中 72 个不在命名为 `client` 的目录中；特性共置本身不是错误，但使 dedicated server 边界更难静态审查。

按源码规模，最大的特性包包括：

| 特性 | Java 文件 | 物理行 |
| --- | ---: | ---: |
| Slime Belt | 13 | 4,720 |
| Magma Belt | 14 | 4,244 |
| Creeper Blast Chamber | 9 | 3,913 |
| Spider Assembly Table | 10 | 2,857 |
| Shulker Teleporter | 8 | 2,266 |
| Ghast Hot Air Balloon | 15 | 2,192 |
| Butter Cat | 27 | 1,932 |
| Power Belt | 10 | 1,921 |

## 2. 网络信任边界

当前 `CBPackets` 注册 13 个数据包，使用顺序递增的 packet id 和 `consumerNetworkThread`。各 handler 必须自行 `enqueueWork`，因此线程安全与输入校验完全依赖逐包实现。

正面观察：

- `PowerBeltSurfaceMovementPacket` 校验发送者、旁观者/飞行状态、有限浮点、加载范围、4 格距离、方块类型、传送带斜率和玩家实际站位，并在服务端限幅。
- `GhastBalloonMagnetTargetPacket` 校验控制权、实体类型、目标距离、目标方块实体和可接受状态。
- `AllayPortConfigurationPacket` 继承精确匹配的 `ref/Create/.../BlockEntityConfigurationPacket.java`，由 Create 基类统一检查旁观/冒险模式、区块加载和 20 格距离。
- `AllayCourierConfirmPacket` 要求发送者当前打开 `AllayCourierMenu`，`confirm()` 再调用 `stillValid()`。

这些模式应成为其他客户端到服务端数据包的统一基线。

### ARCH-01：打包机交互点可被客户端远程改写

- 优先级：P0
- 文件：`ShulkerPackagerPlacementPacket.java`

服务端收到客户端提供的 `ListTag` 和目标 `BlockPos` 后，只检查玩家存在、目标区块已加载、目标是 `ShulkerPackagerBlockEntity`，随后直接调用 `setInteractionPointTag()`。

缺失的校验包括：

- 玩家到目标的距离。
- 玩家是否正在完成该打包机物品的放置流程。
- 玩家手中是否持有匹配物品。
- 交互点数量上限、NBT 总量和每个点与打包机的距离。
- 点是否属于可选择的方块、是否允许当前玩家修改。

客户端 UI 会按连接范围过滤，但服务端不能信任客户端执行了该过滤。恶意客户端可以修改任意已加载打包机的持久化交互点，还可能提交超大列表放大反序列化、区块保存和后续 `initInteractionPoints()` 的成本。

建议：

1. 服务器发送一次性 placement token 或把预期位置/玩家/到期 tick 记录在服务端，客户端回包必须匹配。
2. 校验玩家距离、手持/放置上下文、最大点数和最大连接范围。
3. 服务端逐点重新构造并验证 `ArmInteractionPoint`，只保存规范化后的最小数据，不直接持久化原始客户端 NBT。
4. 若无法证明合法性，拒绝整个包并记录限频警告。

验证：为远距离目标、非打包机、超量点、越界点、伪造 NBT、重复包和正常放置流程添加游戏测试/包处理单元测试。

### ARCH-02：其他客户端数据包的权限与长度校验不一致

- 优先级：P1

具体问题：

- `ShulkerTeleporterConfigPacket` 在读取候选地址时先信任 `size` 并执行 `new ArrayList<>(size)`，之后才通过 normalize 截断到 64 项。应在分配前验证 `0 <= size <= MAX_CANDIDATE_ADDRESSES`，并要求发送者当前菜单就是同一位置的 `ShulkerTeleporterMenu`。
- `SmartSuperGlueRemovalPacket` 允许客户端删除 32 格内任意兼容胶实体，没有验证当前持有智能胶/扳手、交互权限或玩家是否真的选中了该实体；`soundSource` 也完全由客户端指定。
- `SmartSuperGlueSelectionPacket` 消耗物品与生成胶实体前没有统一调用方块交互权限检查，对起点的独立距离限制也不直观。
- `AllayPortConfigurationPacket` 使用无显式上限的 `readUtf()` 读取过滤器；Create 基类解决了距离和模式校验，但没有替子类限制字符串业务长度。
- `AllayCourierConfirmPacket` 和菜单初始化使用无显式上限的 `readUtf()`，应与包裹地址规则共享单一最大长度。
- 服务端到客户端的 Air Courier HUD 包先按远端提供的 count 分配列表；正常服务器可信，但连接不受信服务器时仍应设置条目和物品堆数量上限，避免客户端内存峰值或解码异常。

建议建立共享的包解码工具：有界字符串、有界集合、有限数值、合法枚举；每个 C2S handler 使用统一的 sender/menu/distance/permission/rate-limit 检查模板。

### ARCH-03：网络线程模型容易遗漏主线程切换

- 优先级：P1/P2

当前通道使用 `consumerNetworkThread`，所有 handler 再手工 `enqueueWork`。现有包大多做了切换，Create 的基类包也会切换，但这种结构使新增包很容易直接访问世界。

建议优先使用主线程 consumer 注册方式，或封装一个只接受主线程 handler 的注册 API；解码阶段只做无副作用的长度/格式验证。保留协议测试，确保所有包均声明方向。

同时把 packet id 从“注册顺序决定”改成显式常量/枚举；协议版本仍需手工升级，但代码重排不再悄然改变所有后续 id。

## 3. 模块与初始化边界

### ARCH-04：Allay 与主体形成双向模块依赖

- 优先级：P1

主体入口直接注册 Allay 的三个 server tick 监听器；主体注册表、网络和客户端入口直接导入 Allay 类型。反方向上，Allay 方块实体、屏幕、HUD、实体和渲染又导入主体注册表、网络、纸箱工具和 Mixin accessor。

`com.yision.allay.registry.AllBlockEntityTypes/AllEntityTypes/AllItems/AllMenuTypes` 不是独立注册表，只是对 `CB*` 注册对象的别名。这保留了上游类名，却制造了“看起来独立、实际上反向依赖”的循环。

建议：

- 明确 Allay 是“内置特性模块”而不是第二个模组入口。
- 选定 `CB*` 为唯一注册所有者，逐步删除 `All*` 反向别名；若为减少上游 diff 必须保留，则把它们标记为 compatibility facade，并禁止主体代码反向依赖 facade。
- 为 Allay 建立 `AllayModule.registerCommon/registerClient/registerPackets/registerEvents`，根入口只调用模块 API，不导入具体任务管理器、HUD 或方块类。
- 把主体提供给 Allay 的能力收敛为少量接口，例如 package contents、network sender、registry access，而不是直接导入实现类和 Mixin accessor。

### ARCH-05：注册风格混合且初始化所有权分散

- 优先级：P2

根入口同时使用：

- 主体 `CBBlocks/CBItems/...` 的 DeferredRegister。
- Butter Cat 的独立 `CreateRegistrate`。
- Butter Cat 内部的额外 DeferredRegister。
- `@Mod.EventBusSubscriber` 静态订阅。
- 根入口手工向 Forge event bus 添加 Allay 监听器。

这几种方式单独都可用，但组合后很难快速回答“某功能在哪里初始化、是否只初始化一次、客户端/服务端何时加载”。

建议定义轻量 `FeatureModule` 约定，至少包含 common registration、common setup、client setup、packet registration 和 lifecycle cleanup。不是要求立即改成多 Gradle 模块，而是先统一所有权和调用顺序。

### ARCH-06：静态服务器状态缺少生命周期清理

- 优先级：P1

`AllayCourierTaskManager` 持有静态 `savedData` 和 `courierEntities`；`AllayPortTargetRegistry` 持有按维度索引的静态 `TARGETS`；`AllayCourierHudSync` 也持有静态 HUD 状态。根入口只注册 server starting 和 tick，没有 server stopping 清理。

在同一 JVM 中关闭并重新打开集成服务器时，旧世界的实体引用、目标位置和 tick 时间可能进入新服务器。特别是新服务器 tick 从较小值重新开始时，旧 `lastSeenTick` 可能无法按预期立即过期。

建议：

- 监听 ServerStopping/ServerStopped，显式 discard 视觉实体并清空所有静态 map、savedData 引用和 HUD 状态。
- 更佳方案是把状态挂到 `MinecraftServer`/SavedData/level capability，而不是进程全局 static。
- 添加“世界 A 退出后进入世界 B”的集成测试。

## 4. 客户端与通用代码边界

### ARCH-07：Butter Cat 在通用初始化中加载客户端模型类型

- 优先级：P1
- 状态：已于 2026-08-03 修复代码边界；尚未执行 dedicated server smoke

原实现由 `ButterCatModule.init()` 在通用模组构造阶段调用 `ModPartialModels.init()`，并由 `ButterCatEngineBlockEntity` 直接返回 `PartialModel`。现已删除 Butter Cat 独立模块/注册层：模型声明集中到主客户端 `ButterCatPartials`，模型注册由 `CreateBiotechClient` 完成，方块实体只暴露猫变种、黄油阶段、面包状态等通用数据，renderer、visual 和 JEI 预览器在客户端完成模型映射。

精确匹配的 Create 参考 `ref/Create/src/main/java/com/simibubi/create/CreateClient.java` 只在 `clientInit()` 中调用 `AllPartialModels.init()`。当前项目的做法与 Create 自身的客户端隔离模式不同，存在 dedicated server 类加载风险，也让服务端逻辑依赖渲染库类型。

完成情况：

- [x] 客户端模型声明和注册移到主客户端包/入口。
- [x] 方块实体只暴露通用状态，由客户端消费者映射 `PartialModel`。
- [ ] 添加 dedicated server 启动 smoke；本次为非 Mixin 改动，已通过 `runData` 与 `build`，但未额外启动游戏进程。

### ARCH-08：客户端入口过度集中且有重复注册

- 优先级：P2

`CreateBiotechClient` 同时负责方块实体/实体渲染、模型、覆盖层、资源重载、粒子、Ponder、Flywheel visual、render layer、菜单屏幕、物品属性和 tooltip。它还连续两次为 `CBEntityTypes.CARDBOARD_BOX` 注册同一 renderer。

建议按功能拆为 `ClientRenderers`、`ClientScreens`、`ClientModels`、`ClientReloadListeners`，或由各 FeatureModule 注册客户端部分。先删除重复注册并增加“每个实体类型只注册一次”的静态检查。

### ARCH-09：客户端追踪状态不应与服务端方块实体实现混在同一类

- 优先级：P1/P2

`CreeperBlastChamberBlockEntity` 同时拥有服务端结构/加工状态，以及全局静态 `CLIENT_TRACKED_CREEPERS`、`CLIENT_PRESS_CONTROLLERS` 和多组客户端动画集合。静态 press key 只使用 `BlockPos.asLong()`，不包含维度；虽然实例移除时会尝试清理，但维度切换、区块卸载顺序和位置重用仍使状态所有权难以证明。

建议把客户端全局追踪移到专用 client manager，并以 level/dimension + position 为 key，在 level unload/disconnect 时整体清理。方块实体同步最小表现状态，不直接承担全局渲染查询注册表。

## 5. 大类与重复实现

### ARCH-10：Creeper Blast Chamber 需要按职责提取

- 优先级：P1

约 3,165 行的 `CreeperBlastChamberBlockEntity` 同时承担：

- 多方块结构扫描、形成、破坏和区块加载暂停。
- Vault 角色绑定与物品 capability。
- 压机选择、配方匹配、输出插入和过载爆炸。
- 生物解包、出现、压制、重新打包和超时输出状态机。
- NBT 持久化与网络同步。
- 客户端粒子、压机同步、实体压缩表现。
- 护目镜文本、扳手交互和静态查找工具。

推荐渐进提取：

1. `ChamberStructure`：扫描结果、形成/破坏、vault/press/packager 布局。
2. `ChamberProcessingState`：配方、输入输出、过载计分。
3. `ChamberCreeperLifecycle`：unpack/appearance/packaging/ready output 状态机及 codec。
4. `CreeperBlastChamberClientState`：客户端追踪和视觉效果。
5. 方块实体保留调度、持久化入口和 capability facade。

每次提取先为纯逻辑状态转换补测试，并保持现有 NBT key 兼容。

### ARCH-11：Spider Assembly Table 同时承担菜单、库存、流体与加工计划

- 优先级：P2

约 1,198 行的方块实体包含 hybrid slot 锁定、物品/流体 capability、GUI 操作、运输行为、压制/切割/部署配方选择、加工计划和表现事件。

建议提取 `SpiderAssemblyInventory`/fluid policy、`HybridSlotLockState` 和 `AssemblyProcessController`。特别是“创建 ProcessingPlan”和“应用结果”适合做成无世界依赖的纯逻辑测试。

### ARCH-12：三类自定义传送带存在共享基础层机会

- 优先级：P2

Slime/Magma/Power Belt 三个包合计约 10,885 行。Slime 与 Magma 分别拥有 Block、BlockEntity、Inventory、Slicer、Helper、Renderer、Shapes 等平行结构；自定义 `SlimeBeltInventory` 约 1,165 行，而精确匹配 Create 的 `BeltInventory` 约 485 行。

直接做泛型“大一统”风险很高，因为史莱姆环路、岩浆伤害/表现和动力带发电行为确实不同。建议先提取稳定的小接口和共享算法：连接拓扑、切分/合并快照、形状缓存、漏斗兼容、序列化公共字段；通过差异测试证明行为一致后再合并实现。

## 6. 配置与生成代码

### ARCH-13：Allay 配置只是硬编码包装，不是实际配置

- 优先级：P2

`com.yision.allay.config.AllayClientConfig/AllayServerConfig` 使用简单 record 包装默认值，未接入 ForgeConfigSpec：HUD 固定右上角、缩放固定 0.65、跨维度配送固定开启。与此同时主体已有完整 `CBConfigs` 注册。

建议把这些选项迁入 `CBConfigs` 的 client/server section，或为 AllayModule 注册真实 spec。完成迁移后保留兼容访问 facade 一段时间，避免一次改动所有上游移植代码。

`CBConfigs` 自身约 617 行且 `COMMON` 为空；可把每个特性的 spec builder 拆到对应模块，根类只组合和注册 spec。若 COMMON 长期为空则移除空配置文件。

### ARCH-14：生成 Ponder Java 应隔离为生成 source set

- 优先级：P2

`GeneratedPonderSupport.java` 约 2,179 行，另有多份 generated scene。它们与手写源码混在 `src/main/java`，影响代码统计、审查和格式化，也不清楚如何稳定重建。

建议输出到 `src/generated/java` 并加入单独 source dir，文件头标记生成器版本和输入来源；CI 提供 regenerate-and-diff 检查。手工修复应回到生成器或模板，而不是直接改生成产物。

## 7. 推荐实施顺序

1. P0：修复 Shulker Packager C2S 信任问题，并加入恶意输入测试。
2. P1：统一所有 C2S 包的有界解码、菜单/距离/权限检查和主线程执行模型。
3. P1：补服务器停止清理，验证集成服务器跨世界生命周期。
4. P1：把 Butter Cat PartialModel 与 Creeper Chamber 客户端状态移出通用服务端实现。
5. P1/P2：建立 AllayModule facade，消除双向注册别名。
6. P1/P2：按纯逻辑边界渐进拆分 Creeper Chamber、Spider Table 与 belt 公共算法。
7. P2：整合真实配置、拆客户端入口、隔离生成 Java。

## 8. 验证建议

- 网络：正常客户端、伪造远距离包、超量集合、非法枚举、无菜单、旁观/冒险玩家、重复包。
- 生命周期：专用服务器启动；集成服务器世界 A 退出后进入世界 B；维度切换；区块卸载/重载。
- 兼容：NBT 旧存档迁移、网络协议版本、注册名和配方 id 保持不变。
- 架构：依赖规则测试禁止主体实现反向依赖 Allay facade，禁止 common module 新增 client-only 类型。
