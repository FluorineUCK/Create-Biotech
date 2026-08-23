# Create: Biotech 附属兼容调研与方案

本目录记录 Create: Biotech 与其他机械动力附属之间的兼容工作。
与 [docs/optimization/](../optimization/) 一样，采用“核对一份、落一份文档”的方式推进；
未经 `ref/` 源码核对的判断不会写成确定结论，涉及玩法数值的条目一律标为“待定”。

## 文档

| 文档 | 内容 | 状态 |
| --- | --- | --- |
| [01-addon-processing-machines.zh-CN.md](01-addon-processing-machines.zh-CN.md) | 冶金学、CCA、电气时代、Vintage Improvements 四家加工机械的配方类型、取料方式、热量与能量协议、各自的跨模组做法 | 已完成 |
| [02-processing-compat-plan.zh-CN.md](02-processing-compat-plan.zh-CN.md) | 分层兼容方案、目录约定、可复制的配方模板、候选映射表、风险与分期任务 | 已完成，待设计确认数值 |

## 本轮覆盖的附属

| 附属 | modid | 目标机械 | 1.21.1 可用 | 本地参考 |
| --- | --- | --- | --- | --- |
| 冶金学 Create: Metallurgy | `createmetallurgy` | 动力砂带磨床、工业坩埚（熔化实体）、铸造台/盆 | 是 | `ref/1.21.1/Create-Metallurgy` |
| CCA Create Crafts & Additions | `createaddition` | 轧机、充能 | 是 | `ref/1.21.1/createaddition` |
| 电气时代 Create: New Age | `create_new_age` | 激发器 | 是 | `ref/1.21.1/create-new-age` |
| Create: Vintage Improvements | `vintageimprovements` | 磨床、卷簧机、离心机、振动台、冲压机、杠杆锤、车床、激光加工机 | **否** | `ref/1.20.1/Create-Vintage-Improvements` |

参考仓库的分支、提交与版本吻合度记录在 [ref/SOURCES.md](../../ref/SOURCES.md)。

## 结论摘要

1. 四家附属的加工配方全部基于 Create 的 `ProcessingRecipe` 编解码，
   **跨模组配方是纯数据问题**：在自家命名空间写别家的 `type`，配 `neoforge:mod_loaded` 条件即可，
   不需要任何编译期依赖。冶金学、CCA、VI 三家自己就是这么做的。
2. 本仓库已注册的 `create_biotech:captured_entity_box` 自定义 ingredient
   可以直接用在别家的加工配方里，这是把“生物当原料”推广到第三方机械的关键通道。
3. 三条协议级兼容**已经成立**，只差实机验证：岩浆怪燃烧室对冶金学工业坩埚的供热、
   史莱姆传送带对别家机器的取料、磨床与激发器作为顺序装配步骤。
4. 收益最高、成本最低的一步是补 `c:` 标签（尤其是 `create_biotech:graphite` 进 `c:graphite`，
   目前与 `createmetallurgy:graphite` 互不相认），这一步不引入任何依赖。
5. Vintage Improvements 没有 1.21.1 版本，本轮只做设计预留，不落配方文件。

## 基准

- 调研日期：2026-08-23
- 仓库基线：Minecraft 1.21.1 / NeoForge 21.1.234 / Create 6.0.10-281 / mod 版本 1.3.1
- 分支：`1.21.1`
