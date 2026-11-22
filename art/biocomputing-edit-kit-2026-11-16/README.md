# 美术编辑包（2026-11-16 快照）

## 从这里开始

- **方块：编辑 `01-blocks`。** 按实际注册核对，共 19 个 Biocomputing／保留的关联设备 ID。方块状态、物品模型、父模型和借用的 PNG 一起提供，各设备有独立副本。
- **界面：编辑 `02-ui`。** 收齐当前集成工作树 GUI 图集、精灵与 `.mcmeta`，含 Scratch、pattern 共享皮肤及其他模组页面；未覆盖另一个工作树尚未合入的 UI。
- **备份：`03-reference` 不作为资源包启用。** `original-mod-art.zip` 保留整个当前模组的 641 个原始美术文件（包括其他方块）；`borrowed-originals` 保存实际使用的 Minecraft／Create 原材质与父模型，附带已有许可证。
- `block-index.csv` 可用 Excel 打开。`asset-map.json` 记录原始模型／材质、独立编辑路径与来源文件／JAR。

## 补齐了什么

1. 原 `art/biocomputing/pack` 有 18 个方块入口，`factory_panel` 的状态／物品入口仍在主资源目录，本包补入。
2. 音乐、文字、标签、粒子、朗读、聊天、虚拟过滤器、元件配置等适配器原借用其他材质，没有自己的 PNG。本包复制了独立纹理目录。
3. 仓储连接器输出器原与文本输出器共用模型，现已分开编辑路径。
4. Library 的原版讲台父模型已带上；上半层空模型是现有设计，没有擅自改成实心块。

## 在游戏里预览

将 `01-blocks`、`02-ui` 文件夹（或随附两个资源包 ZIP）放到实例的 `resourcepacks`，启用后 **F3+T** 重载。资源包根直接包含 `pack.mcmeta` 和 `assets`；两个包可同时启用。适用于 Minecraft 1.21.1（格式 34），仍需安装本模组与 Create。

这是**隔离编辑副本**，不是 Gradle 当前默认源目录；编辑不会自动改变构建。原默认美术仍在 `art/biocomputing/pack`，没有移动或删除。定稿后按清单合回，必须把方块状态／物品模型的引用与新模型／PNG 一起带回，不能只覆盖 PNG。后续 agent 不应重新导出覆盖你已编辑的副本。

## 编辑注意

- 模型 JSON 可交给 Blockbench，父模型也在包里；需要父文件时按清单寻找，不要把继承模型当作丢失模型。保持方块 ID、状态键和引用一致。
- 每个设备的模型／纹理位于各自 `art_edit/<方块ID>/...` 中；同一设备的不同面仍可能共用 PNG，要单独改一面可另存 PNG 并调整模型纹理绑定。
- **透明外壳例外：** `textures/block/biocomputing/computer/transparent_casing.png` 和 `_connected.png` 必须保留原 ID，Java 连接纹理逻辑与工厂面板透明框架依赖它们；不要任意改名或裁切。
- 没有重绘或缩放 PNG。动画、九宫格 `.png.mcmeta` 已保留；改尺寸或边框时同步编辑元数据。
- 居民皮肤、动态物品／文字、悬浮标签、粒子轨迹、朗读与音符声音不属于新的方块 PNG。此包不改变程序、布局逻辑或这些运行效果。
- 借用素材保留原 Minecraft／Create 来源，并非本项目原创贴图。

## 方块目录

| ID | 名称 | 模型 | 贴图 | 备注 |
|---|---|---|---|---|
| `factory_panel` | 工厂面板 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/factory_panel/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/factory_panel/) | 补入原美术包遗漏的状态／物品入口；Create stock_ticker 父模型已复制。 |
| `pattern_storage_core` | 样板存储核心 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/pattern_storage_core/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/pattern_storage_core/) | 原版讲台父模型与材质已带上；上半层 elements=[] 是原设计，不是漏模型。 |
| `computer` | 计算机 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/computer/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/computer/) | 上下半模型／束缚部件齐全；居民皮肤由原版实体渲染，不是缺少 Computer PNG。 |
| `biocomputing_data_cable` | 数据线缆 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_data_cable/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_data_cable/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `biocomputing_block_reader` | 方块读取器 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_block_reader/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_block_reader/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `biocomputing_redstone_output` | 红石输出器 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_redstone_output/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_redstone_output/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `biocomputing_stock_link_output` | 仓储连接器输出适配器 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_stock_link_output/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_stock_link_output/) | 原与文本输出器共用模型；本包已拆开，编辑不再连带修改文本输出器。 |
| `biocomputing_text_output` | 文本输出适配器 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_text_output/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_text_output/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `biocomputing_music_output` | 音符输出适配器 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_music_output/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_music_output/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `biocomputing_virtual_filter` | 虚拟过滤控制器 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_virtual_filter/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_virtual_filter/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `biocomputing_component_settings` | 元件配置 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_component_settings/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_component_settings/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `biocomputing_label_output` | 悬浮标签适配器 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_label_output/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_label_output/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `biocomputing_particle_output` | 粒子发射器 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_particle_output/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_particle_output/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `biocomputing_narrator_output` | 音箱 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_narrator_output/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_narrator_output/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `biocomputing_chat_output` | 聊天输出器 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_chat_output/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_chat_output/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `biocomputing_terminal_ui` | 可编程终端 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_terminal_ui/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_terminal_ui/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `biocomputing_cluster_controller` | biocomputing_cluster_controller | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/biocomputing_cluster_controller/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/biocomputing_cluster_controller/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `computer_casing` | 计算机外壳 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/computer_casing/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/art_edit/computer_casing/) | 材质与父模型已独立复制；保持方块 ID 和状态键。 |
| `transparent_computer_casing` | 透明计算机外壳 | [模型](01-blocks/assets/create_biotech/models/block/biocomputing/art_edit/transparent_computer_casing/) | [贴图](01-blocks/assets/create_biotech/textures/block/biocomputing/computer/) | 连接纹理保留原 ID；工厂面板透明框架也使用它，不要任意改名。 |

## 检查范围

已核对注册覆盖、模型／材质引用闭包，逐字节比较 PNG 与原素材，核对模型几何／UV／状态未变、源文件未改。没有进行真实游戏 F3+T 或 Blockbench 可视验收。当前 UI 来源工作树的相关方块 ID 均仍在集成树中。
