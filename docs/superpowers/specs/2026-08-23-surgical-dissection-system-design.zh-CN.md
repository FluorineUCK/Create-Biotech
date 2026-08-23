# 生物切分系统设计与实施方案

## 1. 目标

在 Minecraft 1.21.1 / NeoForge 21.1.234 / Create 6.0.10-281 下实现一套可保存、可同步、可在专用服务端运行的生物切分流程：

1. 新增 `create_biotech:surgical_table`（手术台）。临时外观全部复用 `create_biotech:block/biotech_casing` 纹理，模型为一格宽的台面与四条桌腿。
2. 装有“拟态生物”的小型或大型纸箱普通右击空手术台后，纸箱变空，拟态生物以原模型 1:1 比例横躺在台面上。
3. 玩家手持原版剪刀时，鼠标射线命中的是两个相连 cube 之间的“切缝”，而不是 cube 本体；在命中的切缝平面绘制绿色矩形预览框，右击后断开该连接。
4. 切缝断开后比较两侧连通分量的 cube 数量（相同时保留含最低 cube id 的一侧），较小分量作为一个整体沿“大分量中心指向小分量中心”的方向产生很小的视觉偏移，使切缝清晰可见；偏移不缩放、不拆散分量内部 cube。
5. 空纸箱右击某个 cube 时，收起该 cube 所属的未切分连通分量：
   - 模型初始通过确定性的切缝拓扑组成一个连通图；
   - 剪刀每次只断开鼠标命中的一条切缝；
   - 纸箱收起目标 cube 在当前未断开拓扑中的整个连通分量；
   - 被收起的 cube 从手术台移除，其他分量继续留在台面。
6. 潜行使用装有切分组件的纸箱时，生成真正注册的新实体 `create_biotech:slime_bionic`：
   - 实体注册显示名为“史莱姆仿生体”；
   - 生成实例的自定义名称为“史莱姆？”；
   - 它不是原生物实体的换皮，不复制原生物 AI、属性、物品栏、主人或行为；
   - 它没有目标/寻路 AI，仅受基础物理与伤害规则影响；
   - 它的客户端模型仅由纸箱内保存的 cube 集合组成，并继续使用现有拟态史莱姆 cube 渲染风格。

## 2. 版本与参考基线

- 活跃分支：`1.21.1`。
- 项目依赖：Minecraft `1.21.1`、NeoForge `21.1.234`、Create Maven `6.0.10-281`。
- Create 本地参考：`ref/1.21.1/Create/`，来源记录为 `ref/SOURCES.md` 中官方 `mc1.21.1-6.0.10` 标签 `ac0c444d9828da3453ae8cc65338e8de063286fb`。标签版本与项目的 Create 6.0.10 主版本精确匹配；Maven 构建号不同，但属于同一运行时契约。
- 高亮风格参考：
  - `ref/1.21.1/Create/src/main/java/com/simibubi/create/foundation/blockEntity/behaviour/ValueBox.java`
  - `ref/1.21.1/Create/src/main/java/com/simibubi/create/foundation/blockEntity/behaviour/scrollValue/ScrollValueRenderer.java`
  - `ref/1.21.1/Create/src/main/java/com/simibubi/create/content/kinetics/chainConveyor/ChainConveyorInteractionHandler.java`
- 现有拟态 cube 渲染基础：
  - `src/main/java/com/nobodiiiii/createbiotech/client/render/SlimeMimicRenderLayer.java`
  - `src/main/java/com/nobodiiiii/createbiotech/mixin/client/ModelPartAccessor.java`
  - `src/main/java/com/nobodiiiii/createbiotech/mixin/client/ModelPartRenderMixin.java`

## 3. 核心约束与设计边界

### 3.1 客户端模型与专用服务端隔离

`EntityModel`、`ModelPart`、renderer 和模型 cube 只存在于客户端。公共/服务端代码不得引用这些类型，也不得尝试在服务端烘焙模型。

手术台服务端只保存：

- 来源实体的精简 `MimicProfile`；
- 模型 cube 总数；
- 当前仍在台上的 cube 位集合；
- cube 之间的规范化切缝边列表；
- 已断开的切缝位集合。

客户端从来源实体的真实 renderer/model 中按确定顺序枚举 cube，并按变换后几何构建一棵确定性的最小生成树作为初始切缝拓扑。第一次切缝或装箱请求携带客户端观察到的 cube 总数和拓扑；服务端在硬上限内验证它是覆盖全部 cube 的无环连通树后锁定，后续请求必须严格匹配。

### 3.2 不保存原生物玩法状态

手术台不保存纸箱中的完整实体 NBT，而是在服务端加载纸箱实体后立即调用现有 `MimicProfile.capture`，只保留实体类型、幼体状态和已有白名单内的稳定外观字段。这样避免复制 UUID、生命值、装备、库存、交易、主人、脑记忆和原生物 AI。

### 3.3 cube 编号与切缝拓扑稳定性

客户端在一次原实体模型渲染中按以下顺序编号：

1. 原 renderer 调用各根 `ModelPart` 的顺序；
2. 当前 part 的 `cubes` 列表顺序；
3. 子 part 按名称字典序递归；
4. 同一 `ModelPart.Cube` 实例重复经过渲染层时复用原编号；
5. 完全透明、被现有拟态渲染逻辑判定为不可见的 cube 不编号。

完成 cube 几何采样后，客户端以 cube 表面间距离为边权、以 cube id 作为稳定并列规则，对所有 cube 执行确定性的 Prim 最小生成树，得到恰好 `cubeCount - 1` 条切缝。每条切缝的矩形取较小 cube 朝向另一 cube、且中心距离另一 cube 最近的面；这块矩形才参与剪刀射线命中和绿色预览。

服务端保存 `cubeCount`、规范化边列表与位集合。若某客户端重新生成的 cube 数或拓扑不匹配服务端记录，该客户端仍可显示编号交集，但禁止发起切分/装箱，避免错误切缝被修改。

## 4. 数据模型

### 4.1 `SurgicalAssembly`

新增公共不可变值对象，NBT 版本从 1 开始：

```text
SurgicalAssembly {
  Version: 1
  MimicProfile: {...}
  CubeCount: int
  PresentCubes: long[]
  Seams: int[]          // [a0,b0,a1,b1,...]
  CutSeams: long[]
}
```

约束：

- `1 <= CubeCount <= 1024`；
- 位集合中大于等于 `CubeCount` 的位会被清理；
- `Seams` 必须是覆盖 `0..CubeCount-1` 的规范化无环连通树；
- `CutSeams` 中大于等于切缝数的位会被清理；
- NBT 读取失败、版本不符、profile 无效或集合为空时拒绝释放。

### 4.2 手术台状态

`SurgicalTableBlockEntity` 保存同样的 profile、`cubeCount`、`presentCubes`、`seams`、`cutSeams`。刚放入生物但客户端还未报告模型时 `cubeCount == 0`，此时 profile 有效但不能由服务端自行猜测 cube 或切缝。

### 4.3 纸箱状态

在现有 `CBItemData` 根下增加 `SurgicalAssembly` compound。它与 `CapturedEntity` 互斥。所有“纸箱是否装有内容”的判断扩展为两者任一存在：

- 最大堆叠数为 1；
- 使用捕获模型外观；
- tooltip 显示“史莱姆仿生体”；
- 无法再捕获普通实体或再次放入手术台；
- 释放成功后删除 `SurgicalAssembly`，恢复为空箱。

## 5. 方块与交互

### 5.1 手术台方块

- 水平朝向，放置时正面朝向玩家。
- 非完整方块、无光遮挡、不可寻路。
- 形状由顶部 4 像素厚台面和四条腿组成。
- 实现 `IWrenchable`，扳手可旋转。
- 破坏时若仍有模型，当前第一阶段不返还生物内容；方块实体清除缓存。该行为需通过 tooltip/文档避免误解，后续可单独设计安全回收。

### 5.2 放入拟态生物

普通右击空手术台：

1. 仅接受 `CapturedEntityBoxItem` 且存在 `CapturedEntity`；
2. 服务端临时创建被捕获实体，要求其为 `LivingEntity` 且 `SlimeMimicHandler.isSlimeMimic == true`；
3. 捕获 `MimicProfile`；
4. 写入手术台并同步；
5. 清除纸箱的 `CapturedEntity`，不生成原实体。

非拟态生物、损坏数据、已有内容的手术台均返回 `PASS` 或明确失败提示，不消耗纸箱内容。

### 5.3 切分

客户端命中切缝后发送 `CUT` 请求。服务端验证：

- 玩家、维度、距离和手术台仍有效；
- 交互手确实持有原版剪刀；
- `cubeCount` 合法且匹配；
- 切缝编号存在、两端 cube 仍在台上且切缝尚未断开。

成功后设置 cut-seam 位、消耗剪刀 1 点耐久、播放剪刀声并同步手术台。切分目标不接受 cube id，客户端也不会为 cube 外表面显示剪刀预览。

### 5.4 装箱

客户端命中 cube 后发送 `PACK` 请求。服务端验证手中是完全空的生物纸箱，然后从该 cube 出发，仅沿未断开的切缝执行 BFS，选择完整连通分量。

将选中集合、原拓扑和切缝状态写入一个纸箱的 `SurgicalAssembly`。若玩家手中是多只堆叠空箱，只消耗 1 只并把装好的一只放入物品栏；物品栏满时掉落在玩家位置。成功后从手术台 `presentCubes` 删除该分量；全部取走后清空 profile 与模型状态。

## 6. 客户端渲染与命中

### 6.1 来源实体预览

由 `MimicProfile` 在客户端创建一个从不加入世界的临时 `LivingEntity`，应用稳定外观并标记为拟态。手术台 renderer 直接调用该来源实体自身的 `LivingEntityRenderer`，因此原版和其他模组的实体模型保持兼容。

渲染变换：

- 模型脚点放到手术台中心上方；
- 根据手术台朝向绕 Y 轴旋转；
- 绕 X 轴旋转 90°横躺；
- 沿实体身高方向平移半个身高以居中；
- 不额外缩放，保持 renderer 的 1:1 比例；
- 临时实体不 tick，肢体摆动、头部转动和年龄动画保持冻结。

### 6.2 cube 过滤与偏移

扩展现有 `SlimeMimicRenderLayer` 的 thread-local 渲染上下文。原有拟态替换仍负责把每个源模型 cube 渲染成史莱姆 cube；新的手术上下文只负责：

- 分配/复用稳定 cube 编号；
- 跳过不在 `presentCubes` 中的 cube；
- 对每个不含主锚点的已分离连通分量应用约 `1/16` 方块的统一全局小偏移；
- 在内层 pass 记录 cube 的 8 个变换后角点供命中测试；
- 外层 pass 复用同一编号和偏移，不重复记录。

主锚点为当前最大连通分量；大小相同则取含最低 cube id 的分量。其他分量的偏移方向为主分量几何中心指向该分量几何中心的归一化方向，同一分量内所有 cube 使用完全相同的向量。矩阵使用全局平移，避免肢体自身旋转改变切缝方向。

### 6.3 命中与绿色矩形框

客户端每帧缓存可见手术台 cube 的 8 个世界坐标角点。客户端 tick 时：

1. 仅在玩家手持剪刀时计算切缝预览；
2. 从眼睛位置向视线方向发射长度为方块交互距离的射线；
3. 从未断开的切缝中生成界面矩形，与这些矩形求交并取最近交点；
4. 检查方块遮挡和手术台仍存在；
5. 在 `AFTER_PARTICLES` 阶段使用 `RenderType.lines()` 绘制命中面的四条边。

框颜色采用 Create 现有选择高亮的绿色 `0x68C586`，线条略沿切缝面法线外移以避免深度闪烁。矩形遵循较小 cube 接合面的实际旋转，不退化成世界轴对齐 AABB，也不框选整个 cube。

### 6.4 输入接管

监听客户端 `InputEvent.InteractionKeyMappingTriggered`：只有使用键、无 GUI，并且剪刀命中有效切缝或空纸箱命中有效 cube 时，才取消原输入并发送手术请求。这样即使模型越过一格方块边界，仍可直接操作；其他物品和普通方块交互不受影响。

## 7. 史莱姆仿生体

### 7.1 服务端实体

新增 `SlimeBionicEntity extends PathfinderMob`，注册 id 为 `create_biotech:slime_bionic`：

- 分类 `CREATURE`，常规跟踪范围；
- 固定基础碰撞箱，默认最大生命 10；
- `registerGoals` 为空，并始终 `setNoAi(true)`；
- 生成时设置自定义名“史莱姆？”；
- 用同步的 compound entity data 保存 `SurgicalAssembly`，同时写入实体 NBT；
- 不调用或代理来源生物逻辑。

### 7.2 客户端实体 renderer

renderer 从同步 assembly 的 `MimicProfile` 创建缓存的来源预览实体，调用其原 renderer，并在手术上下文中只放行 `presentCubes`。实体本身仍是 `SlimeBionicEntity`；来源实体仅是未加入世界的客户端模型适配器。

renderer 不显示来源生物名称、阴影或额外 AI 动画。拓扑和切缝状态会保留；renderer 按连通分量统一计算偏移，使组装形状与纸箱中看到的组件一致。

## 8. 注册、资源与本地化

需要修改：

- `CBBlocks`：注册 `SURGICAL_TABLE`；
- `CBItems`：注册对应 BlockItem；
- `CBBlockEntityTypes`：注册手术台 BE；
- `CBEntityTypes`：注册 `SLIME_BIONIC` 及属性；
- `CreateBiotechClient`：注册 BE renderer、实体 renderer；
- `CBPackets`：在现有 serverbound id 列表末尾追加手术交互包并提升网络版本；
- `CBBlockTagsProvider`：加入镐挖掘标签；
- 创造模式标签页：将手术台加入主物品列表（若当前列表按注册集合自动生成则确认无需显式改动）；
- `zh_cn.json`、`en_us.json`：方块、实体、纸箱内容、失败提示和 tooltip；
- blockstate、方块模型、物品模型、loot table、配方与生成标签。

首版手术台配方：

```text
铁板  剪刀  铁板
生化机壳 生化机壳 生化机壳
安山机壳   空   安山机壳
```

若现有材料 tag/配方命名与该草案不一致，以项目已有 recipe 约定改写，但不引入新材质。

## 9. 网络与安全

手术交互包字段：`BlockPos`、`InteractionHand`、动作枚举、目标 id（`CUT` 为 seam id，`PACK` 为 cube id）、`observedCubeCount`、规范化 seam 端点数组。

硬限制与验证：

- `MAX_CUBES = 1024`；
- 拒绝负编号、空模型、数量变化、非树拓扑、重复/越界边、超距、错误维度/方块/物品；
- 所有物品消耗、耐久、组件选择和实体生成均只在服务端完成；
- 客户端只能报告有硬上限且经过树验证的模型编号与拓扑，不能直接提交任意 NBT、profile、位集合或实体类型；
- profile 只来源于服务端已验证纸箱实体；
- assembly NBT 加版本并在每次读取时归一化位集合。

## 10. 生命周期与兼容性

- 客户端预览实体和 cube 快照使用弱引用/短期缓存；世界卸载与资源重载时清理。
- 资源重载后重新枚举 cube；若数量不一致，禁用修改并保留安全显示。
- BE renderer 扩大 render bounding box，使大型 1:1 模型越出台面后不会立即被裁剪。
- 不新增 mixin；只扩展现有 `SlimeMimicRenderLayer` 的公共入口与内部上下文。因此本次属于已有 mixin 影响路径的 Java 改动，编译后检查 refmap 未发生意外目标变化，并按项目规则在可行时运行 `quickPlaySmoke`。
- 来源 renderer 若不是 `LivingEntityRenderer`、profile 无法创建实体或模型没有可见 cube，则显示为空并拒绝交互，不消费后续物品。

## 11. 实施顺序

1. 新增公共 `SurgicalAssembly` 数据类型及纸箱读写/释放支持。
2. 新增手术台 block、BE、注册项与基础资源。
3. 新增服务端交互包和服务端验证/切分/装箱逻辑。
4. 扩展拟态 cube 渲染上下文，输出稳定编号和变换后几何。
5. 新增手术台 renderer、客户端切缝射线命中、绿色切缝框与输入接管；纸箱另用 cube 命中。
6. 新增 `SlimeBionicEntity`、同步数据、实体 renderer 和属性注册。
7. 补齐本地化、配方、模型、loot、tags 和 tooltip。
8. 运行 `compileJava`，修复映射/API 问题；运行资源 JSON 检查与测试。
9. 因修改经过现有 `ModelPart`/`LivingEntityRenderer` mixin 的运行路径，检查生成 refmap，并在环境可行时运行 `quickPlaySmoke` 验证启动、进世界与清理。

## 12. 验收清单

- 空手术台只接受装有拟态生物的纸箱，普通生物纸箱不被消费。
- 放入后纸箱为空，来源实体没有在世界生成。
- 猪、村民、史莱姆等不同体型拟态均以 1:1 比例横躺并保持外观变种/幼体状态。
- 剪刀只在命中有效切缝矩形时显示绿色预览框；遮挡、超距、空台不显示。
- 剪刀光标只命中/预览切缝矩形，不框选 cube；每次剪切只断开目标切缝并消耗 1 点剪刀耐久。
- 切缝断开后较小连通分量整体外移，分量内部 cube 不互相散开。
- 空纸箱点任意 cube 时，收起它通过未断开切缝可达的全部 cube。
- 多个空箱堆叠时只消耗一个箱，装好箱不会堆叠。
- 释放后生成注册实体 `create_biotech:slime_bionic`，显示名“史莱姆仿生体”、实例名“史莱姆？”，没有来源 AI。
- 释放实体只渲染纸箱保存的 cube 集合；重进世界后模型集合不丢失。
- 专用服务端可启动，不加载任何 `net.minecraft.client.*` 类。
- `compileJava` 与 `build` 通过，新增 JSON 资源可解析且被打入产物；本次不改 Mixin 注入点，按项目规则不启动客户端，后续若改变注入点再执行 `quickPlaySmoke`。

## 13. 首版明确不做

- cube 独立物品、掉落物或物理刚体；
- 环状/多重冗余连接拓扑（首版使用确定性最小生成树，每条切缝仍可逐边剪断）；
- 来源实体装备、盔甲层、乘客、库存、交易或 AI 的继承；
- 手术台内容在破坏时的安全回收；
- 动态碰撞箱精确贴合任意 cube 组合；
- 为手术台制作独立新纹理或 Ponder 场景。

这些内容不影响本次用户要求的完整交互闭环，可在系统稳定后单独迭代。
