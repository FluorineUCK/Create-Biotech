# Biocomputing 美术工作目录

编辑 [pack](pack/) 中的文件。这是默认美术的源目录，也是一个可直接加载的 Minecraft 1.21.1 客户端资源包。Gradle 自动把其中的 `assets/` 放入模组 JAR，不需要同步一份到 `src/main/resources`。

## 在游戏里预览

把 `pack` 文件夹复制到测试实例的 `resourcepacks`，可改文件夹名称。确认打开文件夹后直接看到 `pack.mcmeta` 和 `assets/`，再在游戏的资源包列表启用。预览时编辑该副本并按 **F3+T**；定稿后将修改同步回本目录。玩家不安装这个覆盖包时，使用 JAR 内置版本。

## 编辑入口

以下路径均相对于 `pack/assets/create_biotech/`：

| 路径 | 内容 |
| --- | --- |
| `textures/block/biocomputing/` | Computer、透明外壳（含连接纹理）、读取器各面、线缆接头与线段 |
| `blockstates/`、`models/block/`、`models/item/` | 放置状态、Blockbench 可编辑方块模型、对应的物品模型。Library 核心沿用 `pattern_storage_core` 的 ID。 |
| `textures/gui/sprites/pattern/` | 终端、设备、配方等界面共用面板、标题、插槽和按钮状态；沿用已有共享皮肤 ID。 |
| `textures/gui/sprites/biocomputing/` | Scratch 画布、积木底板和接头、嵌套轨道、拖拽／选中提示、值与布尔槽；另有书库搜索框背景 `input.png`。 |
| `textures/gui/pattern_factory_gauge.png` | 配方编辑器界面底图，保留原画布及图集区域。 |

- **方块：**部分设备默认借用 Minecraft／Create 或本项目其他模块的贴图。想单独换它的外观，在本包 `textures/block/biocomputing/` 加 PNG，再改对应模型的 `textures` 或父模型；不需要覆盖借用方的资源。Stock Link Output 默认与文本输出器共享模型，若要独立换装，同时在它的方块状态和物品模型中指向自己的模型。
  元件配置（`biocomputing_component_settings`）的贴图入口是 [component_settings.json](pack/assets/create_biotech/models/block/biocomputing/component_settings.json)：顶部和侧面默认借用本模组的 `asurine_casing`，正面借用原版 `repeater`，没有专用 PNG。界面沿用上表的 `pattern/` 共享皮肤。
  波导的[放置模型](pack/assets/create_biotech/blockstates/biocomputing_carrier_cable.json)将深色输入臂、铜色输出臂分别朝向实际连接面；物品栏与其他数据线缆共用横向直线模型，保留深色输入端与铜色输出端；两端的物品贴图在 [carrier_cable.json](pack/assets/create_biotech/models/block/biocomputing/carrier_cable.json) 中调整。旧独立分频器与倍频器的美术已[归档](archive/carrier-converters/)，不随资源包发布。
- **界面：**优先在原尺寸下替换 PNG。带 `.png.mcmeta` 的精灵使用九宫格，修改逻辑尺寸或边框时同步修改元数据。积木底板和接头分别编辑；布尔槽的两端随槽高缩放。文字、布局、点击和拖拽区域不在贴图里。沿用原版或 Create 的通用控件仍由它们提供。
- [reference-assets](reference-assets/) 是参考素材，不打包，不是运行时替换入口。
