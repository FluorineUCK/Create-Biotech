# 09. 史莱姆传送带 Create 兼容差异与修复方案

## 1. 文档目标

本文记录史莱姆传送带相对 Create 原版传送带的行为差异、问题优先级和大致修复方式。
比较标准是最终玩法语义和 Create 兼容契约，不是代码是否逐行相同。

本文同时固化以下产品决定：

> 粉碎轮、机械臂、隧道、显示链接、智能观察者、风扇、工作盆和普通物品能力等
> Create 外围交互，只暴露史莱姆传送带的 `Track.FRONT`，行为与原版传送带的单一工作面一致。
> 漏斗是唯一允许根据物理接触面分别访问 `Track.FRONT` 和 `Track.BACK` 的外围设备。

这条约束的目的，是保留史莱姆带的双面回环玩法，同时避免把 Create 的每一种传送带外围设备都扩展成
双面设备。后续实现不得为了复用漏斗表面解析而让粉碎轮、机械臂、隧道或通用行为访问 BACK。

## 2. 审计基准与置信度

- 当前分支：`1.21.1`。
- Minecraft：`1.21.1`。
- NeoForge：`21.1.219`。
- Create 运行依赖：`6.0.10-281`。
- Create 本地参考：`ref/1.21.1/Create`。
- 参考版本：官方标签 `mc1.21.1-6.0.10`，提交
  `ac0c444d9828da3453ae8cc65338e8de063286fb`。
- 版本来源：`gradle.properties` 与 `ref/SOURCES.md`。

本文基于静态源码审计。结论使用以下标记：

| 标记 | 含义 |
| --- | --- |
| 静态确认 | 从注册、类型判断和调用路径可以直接确定结果 |
| 推断 | 代码可以推导出高概率影响，但具体表现仍应运行验证 |
| 需产品确认 | 与原版不同，但是否保留取决于玩法决定 |
| 需实机 | 必须通过客户端或 GameTest 确认完整行为 |

## 3. 已确认的交互边界

### 3.1 FRONT 的定义

本文中的“正面”统一指 `SlimeBeltLoopGeometry.Track.FRONT`，不是“玩家当前点击到的任意一面”。

- 水平史莱姆带的 FRONT 对应原版传送带上方工作面。
- 斜坡的 FRONT 对应原版传送带可放置、可加工物品的外侧工作面。
- 竖直和 SIDEWAYS 仍可保留史莱姆带内部运物能力，但 Create 原版不支持的外围交互不得因此扩展。
- `Direction.UP` 或无方向能力查询继续映射到 FRONT。
- BACK 只参与史莱姆带内部回环、掉落物的物理接触捕获，以及漏斗的专用双面交互。

### 3.2 漏斗是唯一双面外围交互

以下现有结构应继续只服务漏斗：

- `content/beltsurface/BeltSurfaceProviderBlock.java`
- `content/beltsurface/BeltSurfaceHost.java`
- `content/beltsurface/BeltSurfaceResolver.java`
- `content/beltsurface/FunnelInteractionCore.java`
- 漏斗相关 Mixin 中的表面状态和解析逻辑

非漏斗兼容代码不得调用 `surfaceFor()` 或枚举 `surfaces()`。粉碎轮、机械臂、隧道等应直接使用
FRONT 的标准传送行为或专用 FRONT 适配器，而不是复用漏斗的双面抽象。

### 3.3 目标交互矩阵

| 交互 | FRONT | BACK | 形态限制 | 备注 |
| --- | --- | --- | --- | --- |
| 史莱姆带内部回环 | 是 | 是 | 史莱姆带支持的全部形态 | 核心功能 |
| 掉落物物理捕获 | 是 | 是 | 按真实接触面 | 核心功能，不属于外围 I/O |
| Create 漏斗 | 是 | 是 | 必须贴合实际暴露表面 | 唯一双面外围交互 |
| 通用物品能力 | 是 | 否 | 与原版能力语义一致 | 无方向和 `UP` 均为 FRONT |
| 包裹右击插入 | 是 | 否 | 只接受 FRONT 点击 | BACK 点击应 `PASS`，不得偷偷路由 FRONT |
| 空手直接拾取 | 是 | 否 | 只检查 FRONT | 不再从 FRONT 穿透拾取 BACK |
| 机械加工 | 是 | 否 | 当前只允许水平带 | 保持现有产品边界 |
| 横向粉碎轮 | 是 | 否 | 与原版一样仅水平有效 | 不创建 BACK 粉碎入口 |
| 安山/黄铜隧道 | 是 | 否 | 水平带上方 | 不允许底部 BACK 隧道 |
| 机械臂 | 是 | 否 | 对齐原版 `BeltType` 支持形态 | 每段最多一个交互点 |
| Display Link | 是 | 否 | 对齐原版物品名称源 | 使用现有通用行为 |
| 智能观察者 | 是 | 否 | 对齐原版 | 当前 FRONT-only 行为是正确设计 |
| 鼓风机加工 | 是 | 否 | 对齐原版 | 当前 FRONT-only 行为是正确设计 |
| 工作盆输出 | 是 | 否 | 对齐原版 | 停转时也可被选为输出方向 |
| 蓝图、装置移动 | 不分轨道 | 不分轨道 | 必须按整条链处理 | 结构级兼容 |

## 4. 不应作为缺陷修复的刻意功能

以下差异属于已确认或合理的产品功能：

1. 物品在末端转入背面，而不是像原版传送带一样抛出。
2. FRONT/BACK 构成闭合回环。
3. 史莱姆带可以竖直运输物品。
4. 竖直和 SIDEWAYS 不运输实体。
5. 掉落物按真实接触面进入最近轨道，某一面拥堵时不改投另一面。
6. 漏斗可以贴合不同暴露面，并分别操作 FRONT 或 BACK。
7. 初始化失败后每 20 tick 重试，避免跨未加载区块时每 tick 重扫整链。
8. 不染色、不清洗、没有普通 Create 传送带机壳，可继续作为材质和视觉身份差异。

第 8 项不能用于解释“隧道完全不可用”。隧道兼容应通过明确的史莱姆带分支实现，不必为了隧道
给史莱姆带完整复制原版 `CASING` 状态和染色系统。

## 5. 问题与大致修复方式

### 5.1 P0：Create 隧道完全不支持

状态：静态确认。

现状：

- `README.zh-CN.md` 宣称自定义传送带支持漏斗和通道。
- Create `BeltTunnelBlock#isValidPositionForPlacement` 硬编码 `AllBlocks.BELT`。
- Create `BeltTunnelBlock#canSurvive` 读取 `BeltBlock.CASING`。
- 史莱姆带没有 `CASING` 属性。
- `SlimeBeltInventory` 在漏斗后直接进入相邻传送，没有隧道 flap、阻塞或分配处理。
- 安山隧道能力和黄铜隧道分配逻辑分别硬编码原版传送带类型。

修复方向：

1. 为隧道放置和生存判断增加显式史莱姆带分支，只接受水平史莱姆带上方的 FRONT。
2. 不使用 `BeltSurfaceResolver`，也不允许隧道放在底部访问 BACK。
3. 保持史莱姆带没有 `CASING` 属性；隧道的史莱姆带分支使用独立的有效性判断。
4. 新增 `SlimeBeltTunnelInteractionHandler`，或提取仅面向单一正面的共享隧道算法。
5. 处理 flap、窗口阻塞、安山隧道能力转发和黄铜隧道分配。
6. 隧道读写物品时始终使用 FRONT handler，不根据隧道所在方向自动选择 BACK。
7. 修改隧道相邻更新，让史莱姆带拆除、切分或失效时正确更新/拆除隧道。

建议参考：

- `ref/1.21.1/Create/.../logistics/tunnel/BeltTunnelBlock.java`
- `ref/1.21.1/Create/.../logistics/tunnel/BeltTunnelBlockEntity.java`
- `ref/1.21.1/Create/.../logistics/tunnel/BrassTunnelBlockEntity.java`
- `content/magmabelt/transport/MagmaBeltTunnelInteractionHandler.java`

验收要求：隧道只在水平史莱姆带上方放置；FRONT 物品正确阻塞、开 flap 和分配；BACK 物品完全不受影响。

### 5.2 P0：蓝图炮和移动蓝图部署器不能可靠重建链

状态：蓝图炮失败为静态确认；移动部署器需实机确认具体表现。

现状：

- `SlimeBeltBlock` 没有实现 `SpecialBlockItemRequirement`。
- 每一段会按普通方块消耗一个史莱姆带连接器，而不是按整链计算轴和连接器。
- `SchematicannonBlockEntity` 只对 `AllBlocks.BELT` 原子发射整条链。
- 史莱姆带单段初始化时会自毁，而普通方块炮弹至少需要 10 tick 到达。
- `DeployerMovementBehaviour` 只跳过原版传送带，会把史莱姆带当普通方块逐段放置。

修复方向：

1. 让 `SlimeBeltBlock` 实现 `SpecialBlockItemRequirement`，费用规则对齐原版：起点消耗连接器，端点/滑轮消耗轴。
2. 为蓝图炮增加史莱姆整链识别和一次性放置路径；不能先放单段再等待后续段。
3. 整链载荷至少包含起点、方向、坡度、长度、各段 `PART` 和必要方块实体数据。
4. 移动部署器应跳过普通逐段逻辑，或调用同一个整链放置入口。
5. 放置完成后只初始化一次链，不在中间状态触发自毁和物品同步。

验收要求：水平、斜坡、竖直和含滑轮链均能通过蓝图炮和移动部署器恢复，材料数量正确且不重复掉落。

### 5.3 P1：移动装置不会自动收集整条史莱姆带

状态：整链自动收集缺失为静态确认；残链和物品结果需实机。

现状：Create `Contraption#moveBlock` 只为 `AllBlocks.BELT` 调用 `moveBelt()`。史莱姆带按普通方块的
胶水和邻接规则收集，可能只移动链中的部分段。

修复方向：

1. 在装置结构搜索阶段识别任意史莱姆带段并把完整链加入 frontier。
2. 复用 `SlimeBeltBlock#getBeltChain` 或抽出只读整链枚举方法。
3. 确保每条链只枚举一次，并正确处理跨区块未加载。
4. 组装前统一捕获链上物品；拆卸并重新初始化后恢复，失败时安全弹出。
5. 不把 FRONT/BACK 当成两个装置成员；装置移动的是物理链。

验收要求：胶住任意一段时整链一起移动；不丢物、不复制物品、不留下孤立段。

### 5.4 P1：缺少横向粉碎轮交互

状态：静态确认。

修复方向：

1. 参考 Create `BeltCrusherInteractionHandler` 和现有
   `MagmaBeltCrusherInteractionHandler` 增加史莱姆带处理器。
2. 只在 `BeltSlope.HORIZONTAL` 且物品位于 `Track.FRONT` 时调用。
3. 调用顺序对齐原版：机械加工、漏斗、隧道、粉碎轮、移动。
4. BACK 物品经过同一几何位置时不得触发粉碎轮，也不得因 FRONT 粉碎轮阻塞。
5. 粉碎轮输出继续回到 FRONT，不进入回环的 BACK。

验收要求：FRONT 与原版传送带表现一致；BACK 在粉碎轮下方完整通过且不触发配方。

### 5.5 P1：机械臂不识别史莱姆带

状态：静态确认。

修复方向：

1. 在 `CBArmInteractionPointTypes` 注册史莱姆带专用 `ArmInteractionPointType`。
2. `canCreatePoint` 对齐原版 `BeltType` 的坡度和隧道限制，但识别 `CBBlocks.SLIME_BELT`。
3. 每个物理段只创建一个 FRONT 交互点。
4. 交互点使用现有 `TransportedItemStackHandlerBehaviour`；该行为当前固定 FRONT，符合产品决定。
5. 不使用 `BeltSurfaceHost`，不为 BACK 创建第二个点，也不根据机械臂朝向切换轨道。

验收要求：机械臂可从 FRONT 取放；BACK 物品不可见；带上方有隧道时限制与原版一致。

### 5.6 P1：Display Link 未注册；观察者和风扇只需验证

状态：Display Link 缺失为静态确认。

修复方向：

1. 为史莱姆带注册 Create `ITEM_NAMES` Display Source。
2. 继续使用 `TransportedItemStackHandlerBehaviour`，因此只显示 FRONT 物品。
3. 智能观察者和鼓风机已经通过该行为只访问 FRONT，不应改成双面。
4. 增加回归测试，防止以后为漏斗扩展表面时意外让观察者、显示链接或风扇访问 BACK。

验收要求：FRONT 与原版一致；只有 BACK 有物品时显示链接为空、观察者不触发、风扇不加工。

### 5.7 P2：停转史莱姆带不能成为工作盆输出

状态：静态确认。

现状：原版工作盆把速度为零的 `BeltBlockEntity` 视为合法输出，史莱姆带的
`DirectBeltInputBehaviour#canInsertFrom` 在速度为零时返回 false。

修复方向：

1. 在工作盆输出方向判断中显式识别史莱姆带，复现原版停转传送带特例。
2. 实际输出插入仍走 FRONT。
3. 不放宽普通相邻机器向停转史莱姆带插入的规则，避免改变其他物流语义。

### 5.8 P2：先放漏斗、后放史莱姆带不会自动附着

状态：静态确认。

现状：`FunnelBlockMixin` 中相邻更新注入因崩溃被注释，玩家必须拆掉并重放漏斗。

修复方向：

1. 定位重入来源，不直接恢复旧注入。
2. 将状态计算和 `setBlock` 分离，必要时延迟一个服务端 tick 更新。
3. 更新前再次验证漏斗、史莱姆带和目标表面仍存在。
4. 该修复属于漏斗专用逻辑，可以继续使用 `BeltSurfaceResolver`。

### 5.9 P2：双面原生交互仍有语义不一致

状态：静态确认，具体手感需实机。

修复方向：

1. 包裹右击只在命中 FRONT 时插入；命中 BACK 时返回 `PASS`。
2. 空手拾取只查 FRONT，删除“正面取不到就尝试背面”的兜底。
3. 掉落物实体继续按真实接触面进入 FRONT 或 BACK，这是内部物理捕获，不受外围 FRONT-only 约束。
4. 破坏、切分失败或整链失效时，弹出速度应按物品所在轨道的局部运动切线计算；BACK 不得固定沿正链方向弹出。

### 5.10 P2：缺少史莱姆带 Flywheel Visual

状态：性能影响为推断。

修复方向：

1. 参考 `MagmaBeltVisual` 和 Create `BeltVisual` 实现 `SlimeBeltVisual`。
2. 在 `CreateBiotechClient` 注册并按 `shouldRenderNormally()` 跳过普通带面渲染。
3. 物品渲染仍由控制器负责，避免 Visual 和 BER 各画一次。
4. 验证关闭 Flywheel、开启 Flywheel、跨区块和长链四种情况。

### 5.11 P3：堆叠上限、可燃性和 SIDEWAYS 状态

状态：前两项静态确认；SIDEWAYS 需产品确认。

修复方向：

1. `SlimeItemHandlerBeltSegment#getSlotLimit` 改为读取 `DataComponents.MAX_STACK_SIZE`，对齐 Create 1.21.1。
2. 若史莱姆带不应燃烧，覆盖 `isFlammable` 返回 false；若刻意可燃，则在玩法文档明确。
3. 决定是否完成竖轴连接。完成前应把 SIDEWAYS 标为未支持，不为其扩展粉碎轮、隧道或机械臂。
4. 若长期不支持 SIDEWAYS，删除不可达建造和渲染分支前先验证旧世界兼容需求。

### 5.12 P3：物品排序和切分同步开销

状态：静态调用结构确认，规模影响需压测。

修复方向：

1. `SlimeBeltSlicer#restoreSnapshots` 先恢复一组物品，再对每个控制器统一 `setChanged()` 和 `sendData()`。
2. 避免每加入一个物品就重新序列化不断增长的完整列表。
3. 评估是否可以维护稳定运动顺序，减少每 tick `OrderedItem` 分配和完整排序。
4. 如果物品量上限使排序收益很小，保留排序但补充原因和压测结果，不做无证据重构。

## 6. 可能的冗余与清理候选

以下项目不阻断玩法，应随相关改动清理：

1. `SlimeBeltSlicer.Feedback` 是空类，`useWrench`/`useConnector` 的 `hand` 和 `feedback` 未使用。
2. `FunnelInteractionCore` 的 `ItemHandlerHelper` import 未使用。
3. `SlimeBeltHelper` 复制了 Create `BeltHelper` 的直立物品缓存和重载监听器，可直接调用 Create helper。
4. SIDEWAYS 若不开放建造，其状态、形状、运输和渲染分支可能成为休眠代码。
5. 切分后的物品定位使用全环粗采样加局部细采样；可在有性能证据后换成解析投影。

清理时不得把漏斗双面解析推广成通用传送带表面框架。当前明确目标是“漏斗特殊、其余 FRONT-only”。

## 7. 建议实施顺序

### 阶段 A：固化交互边界和阻断性兼容

1. 为 FRONT-only 与漏斗双面行为增加测试夹具。
2. 修复隧道放置、生存、flap、阻塞和黄铜分配。
3. 修复蓝图炮与移动部署器的整链放置。

### 阶段 B：补齐 Create 常用外围设备

1. 横向粉碎轮。
2. 机械臂。
3. Display Link。
4. 工作盆停转输出。
5. 修复漏斗后放自动附着。

### 阶段 C：结构移动和表现

1. 移动装置整链收集、物品保存和拆卸恢复。
2. Flywheel Visual。
3. 弹出方向、包裹点击和空手拾取一致性。

### 阶段 D：低风险一致性和清理

1. 数据组件堆叠上限。
2. 可燃性决定。
3. SIDEWAYS 决策。
4. 批量同步和排序压测。
5. 删除确认无用的参数、import 和重复 helper。

## 8. 验收矩阵

每一种外围设备都必须同时验证 FRONT 和 BACK，避免只验证“能工作”却误开放背面。

| 场景 | FRONT 预期 | BACK 预期 |
| --- | --- | --- |
| 漏斗插入、抽取、过滤、阻塞 | 工作 | 工作 |
| 粉碎轮 | 与原版一致 | 完全忽略 |
| 机械臂取放 | 与原版一致 | 不可见 |
| 安山隧道 | flap 和阻塞正常 | 完全忽略 |
| 黄铜隧道 | 分配正常 | 完全忽略 |
| Display Link | 显示物品名称 | 不显示 |
| 智能观察者 | 可检测 | 不检测 |
| 鼓风机加工 | 可加工 | 不加工 |
| 工作盆输出 | 可接收，停转也可选方向 | 不接收 |
| 包裹右击 | 可插入 | `PASS` |
| 空手拾取 | 可拾取 | 不穿透拾取 |

结构级验证：

- 蓝图炮一次恢复完整链，材料消耗正确。
- 移动蓝图部署器不会逐段留下自毁链。
- 装置胶住任意一段时收集整链。
- 装置拆卸后控制器、长度、索引和物品位置正确。
- 切分、合并、反转、跨区块卸载后无复制、吞物和持续同步包。

工程验证：

- 普通 Java 改动至少运行 `./gradlew compileJava`。
- 涉及 Mixin 的隧道、装置或工作盆改动检查生成的 `create_biotech.refmap.json`。
- 涉及运行时注入的改动在可行时运行带超时和清理的 `./gradlew quickPlayClient`。
- 为 FRONT/BACK 交互矩阵补充 GameTest；在自动化覆盖前同步勾选
  `docs/porting/1.21.1-neoforge-api-and-test-matrix.zh-CN.md` 中实际完成的项目。

## 9. 主要受影响文件

史莱姆带核心：

- `src/main/java/com/nobodiiiii/createbiotech/content/slimebelt/SlimeBeltBlock.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/slimebelt/SlimeBeltBlockEntity.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/slimebelt/SlimeBeltSlicer.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/slimebelt/transport/SlimeBeltInventory.java`
- `src/main/java/com/nobodiiiii/createbiotech/content/slimebelt/transport/SlimeItemHandlerBeltSegment.java`

漏斗专用层：

- `src/main/java/com/nobodiiiii/createbiotech/content/beltsurface/`
- `src/main/java/com/nobodiiiii/createbiotech/mixin/FunnelBlockMixin.java`
- 其他漏斗相关 Mixin

注册与客户端：

- `src/main/java/com/nobodiiiii/createbiotech/registry/CBArmInteractionPointTypes.java`
- `src/main/java/com/nobodiiiii/createbiotech/client/CreateBiotechClient.java`

预计新增或扩展的兼容层：

- 史莱姆带 FRONT-only 隧道处理器
- 史莱姆带 FRONT-only 粉碎轮处理器
- 史莱姆带机械臂交互点
- 史莱姆带 Flywheel Visual
- 隧道、蓝图炮、装置和工作盆的最小范围 Mixin 或 API 注册

## 10. 明确不采用的方向

1. 不把“所有外部设备都能根据安装方向访问 FRONT/BACK”作为目标。
2. 不为机械臂创建正反两个交互点。
3. 不允许底部隧道访问 BACK。
4. 不让显示链接、智能观察者或风扇遍历两条轨道。
5. 不把 `BeltSurfaceResolver` 变成所有传送带兼容的公共入口。
6. 不因实现隧道而强制复制原版传送带完整染色、清洗和机壳系统。
7. 不用逐段延迟初始化规避蓝图问题；蓝图和装置必须按完整物理链处理。

最终架构应保持清晰边界：史莱姆带内部是双面回环，漏斗是唯一双面外围适配，其余 Create 设备看到的
仍是一条只有 FRONT 工作面的标准传送带。
