# 输入／输出设备美术参考素材

从当前项目使用的本地资源提取，保持原文件名、像素、模型与 UV，不替换任何模组的实际运行资源。
此目录不会打包；本模块的运行美术使用 [独立美术包](../pack/)。
每组保留 `assets/<namespace>/...` 结构，包含模型递归引用的父模型和纹理，便于独立查阅。

来源：
- Create **6.0.10-281**：项目 Gradle 缓存中的 `create-1.21.1-6.0.10-281.jar`。
- Minecraft **1.21.1**：`build/moddev/artifacts/neoforge-21.1.234-client-extra-aka-minecraft-resources.jar`。
- Create 原包许可原文保留在 `create-smart-observer/CREATE-LICENSE.md`。
- `sources.json` 列出每组的原始资源路径与来源包。这些是原模组／游戏资源，不是本项目原创美术。

## 1. Create 智能侦测器

游戏注册名为 `create:content_observer`，纹理主要叫 `smart_observer_*`。

- [纹理目录](create-smart-observer/assets/create/textures/block/)
- [普通模型](create-smart-observer/assets/create/models/block/content_observer/block.json)
- [亮起模型](create-smart-observer/assets/create/models/block/content_observer/block_powered.json)
- [放置方向与状态](create-smart-observer/assets/create/blockstates/content_observer.json)

当前模型朝北时：
- 正面：`smart_observer_front.png`。
- 背面：`smart_observer.png` / `smart_observer_powered.png`。
- 左右侧面：`smart_observer_side.png` / `smart_observer_side_powered.png`。
- 顶面／底面：`smart_observer_top.png` / `smart_observer_bottom.png`。
- 同时提取了包内的 `content_observer*.png`（包含 inner 和 powered）供参考；
  **当前上述方块模型没有引用这四张纹理**，不要据此误认它们是当前正面。

## 2. Minecraft 发射器

- [纹理目录](minecraft-dispenser/assets/minecraft/textures/block/)
- [水平模型](minecraft-dispenser/assets/minecraft/models/block/dispenser.json)
- [垂直模型](minecraft-dispenser/assets/minecraft/models/block/dispenser_vertical.json)
- 正面：`dispenser_front.png` / `dispenser_front_vertical.png`。
- 侧面及顶面复用 `furnace_side.png` / `furnace_top.png`，已经一并提取。
- 容器界面：`assets/minecraft/textures/gui/container/dispenser.png`。

## 3. Minecraft 投掷器

- [纹理目录](minecraft-dropper/assets/minecraft/textures/block/)
- [水平模型](minecraft-dropper/assets/minecraft/models/block/dropper.json)
- [垂直模型](minecraft-dropper/assets/minecraft/models/block/dropper_vertical.json)
- 正面：`dropper_front.png` / `dropper_front_vertical.png`。
- 共用的 `furnace_side.png` / `furnace_top.png` 已补齐。
- 界面也使用 `dispenser.png`，不是漏掉了 `dropper.png`。

## 4. Minecraft 合成器

- [方块纹理目录](minecraft-crafter/assets/minecraft/textures/block/)
- [普通模型](minecraft-crafter/assets/minecraft/models/block/crafter.json)
- [触发模型](minecraft-crafter/assets/minecraft/models/block/crafter_triggered.json)
- [合成中模型](minecraft-crafter/assets/minecraft/models/block/crafter_crafting.json)
- [合成且触发模型](minecraft-crafter/assets/minecraft/models/block/crafter_crafting_triggered.json)
- [界面背景](minecraft-crafter/assets/minecraft/textures/gui/container/crafter.png)
- [禁用槽及红石图标](minecraft-crafter/assets/minecraft/textures/gui/sprites/container/crafter/)

包括 top / bottom / north / south / east / west 及各实际存在的 triggered / crafting 变体。
不是每面每个状态都有独立纹理；模型按原版方式复用未变化的面。

## 用于本模块

可用像素画工具修改 PNG，用 Blockbench 查阅或导入 Java Block/Item 模型；编辑继承模型时也参考已附的父模型。
模型中的资源 ID 保留原样，导入工具若不能自动解析 `assets/`，按纹理目录手动指定对应 PNG。

应用到 Biocomputing 时，将选定图片另存到 `create_biotech` 自有目录，修改本模块的模型引用；
不要直接替换 Minecraft／Create 命名空间的图片，否则会影响原方块。当前读取器与线缆的独立纹理在
`../pack/assets/create_biotech/textures/block/biocomputing/`。
