# 07. 综合优先级与分阶段实施路线图

## 总结

仓库当前可以编译和打包，但还不适合把“构建成功”当成可发布、可兼容或可扩展的证明。最优实施顺序不是先拆 3000 行大类，而是先封住客户端信任、错误发布、资源缺失和状态泄漏，再建立自动化门禁；有了测试与诊断后，再处理 Mixin 映射、Blast Chamber 热点和 Allay/主体边界。

本路线图汇总：

- [01 构建、依赖与发布](01-build-dependencies-release.zh-CN.md)
- [02 Java 架构、网络与客户端边界](02-java-architecture-network.zh-CN.md)
- [03 Mixin、映射与兼容](03-mixin-compatibility.zh-CN.md)
- [04 资源、数据生成与本地化](04-resources-datagen-localization.zh-CN.md)
- [05 运行时性能与生命周期](05-runtime-performance.zh-CN.md)
- [06 测试、诊断、CI 与开发体验](06-testing-diagnostics-developer-experience.zh-CN.md)

本次审计以提交 `511533e2ef52c25dfa6f65bab5d19ebd49e063c2` 为代码基准；文档编写期间没有修改产品源码或资源。

## 1. 必须优先处理的风险

| 排名 | 优先级 | 工作项 | 主要风险 | 来源 |
| ---: | --- | --- | --- | --- |
| 1 | P0 | 加固 `ShulkerPackagerPlacementPacket` | 恶意客户端可远程改写已加载打包机的任意交互点 NBT | ARCH-01 |
| 2 | P0 | 修复发布脚本失败仍成功退出、错误解析 version type | 上传失败/错误 channel仍被报告为发布完成 | BUILD-02/03 |
| 3 | P0 | 把 LICENSE 与 THIRD_PARTY_NOTICES 打入 JAR并修正许可证表述 | 发布产物许可证不完整且 `mods.toml` 误导 | BUILD-01 |
| 4 | P0/P1 | 修复 `schrodingers_cat` 缺失模型 parent | 已由客户端日志复现的资源加载错误 | RESOURCE-01 |
| 5 | P0/P1 | 修复 Mixin damage context 的异常不安全 push/pop | 异常后 ThreadLocal 污染，后续伤害归因错误 | MIXIN-04 |
| 6 | P0/P1 | 移除全 LivingEntity 每 tick persistent NBT 热写 | 全局实体热路径、存档膨胀、双端重复成本 | PERF-05 |
| 7 | P0/P1 | 建 Java 17 CI 与网络边界测试 | 当前 `test NO-SOURCE`，所有上述回归都无自动门禁 | CI-01、TEST-02 |
| 8 | P1 | 修正高风险 `remap = false` 并验证 refmap/runtime | 版本/生产环境中 Mixin target可能无法映射 | MIXIN-01/09 |
| 9 | P1 | 统一 server/client level lifecycle cleanup | 集成服务器换世界后 static状态与旧实体引用残留 | ARCH-06、PERF-06/15/16 |
| 10 | P1 | 缓存 Blast Chamber 结构与实体索引 | 上限结构存在每 tick全扫描和客户端近似 O(N²) 查找 | PERF-01/02/03 |
| 11 | P1 | 加固其他 C2S decoder、菜单、距离与权限 | 远端 count大分配、Smart Glue越权、字符串无业务上限 | ARCH-02/03 |
| 12 | P1 | 修复 Butter Cat common/client 类加载边界 | dedicated server潜在加载 Flywheel client model类型 | ARCH-07 |
| 13 | P1 | 修复不可复现/不受约束的发布与依赖契约 | 相同源码 JAR hash不同、版本范围超出验证窗口 | BUILD-04/06/07/08 |
| 14 | P1 | 明确 datagen/Ponder 生成源并修复 manifest漂移 | 下次导出可能覆盖翻译、删除/遗漏资源 | RESOURCE-03/04、DX-04 |
| 15 | P1 | 修复 ref 与项目规则不可复现 | clean clone没有本地权威参考和 Mixin约定 | DX-01/02/03 |

## 2. 阶段 0：发布与安全止血

建议周期：1–3 天。目标是阻止已知不合规发布和明显远程滥用，不做大重构。

### 工作包 0A：网络止血

1. 为 `ShulkerPackagerPlacementPacket` 增加：
   - 有界 NBT/点数量；
   - sender、距离、区块、BE 类型；
   - 手持物品/放置上下文；
   - 服务端 placement token；
   - 每个点的服务端重建与范围/权限校验。
2. `ShulkerTeleporterConfigPacket` 在 `new ArrayList<>(size)` 前拒绝负数与 `>64`。
3. Smart Glue删除/选择校验工具、距离、权限和 server-side selection。
4. `AllayCourierConfirmPacket`、Allay filter和 HUD count使用显式上限。

验收：恶意输入测试全部拒绝且不产生部分 world修改；正常客户端流程保持兼容。

### 工作包 0B：发布止血

1. 发布 JAR包含 `LICENSE.md` 和 `THIRD_PARTY_NOTICES.md`。
2. `mods.toml` 不再把整个混合资产包简单声明为 MIT；README、JAR和平台说明一致。
3. `publish.py` 汇总每个平台结果，只要必需上传失败就非零退出；打印“发布完成”前验证返回值。
4. 参数改为 argparse，并同时正确支持 `--version-type beta`；未知参数直接失败。
5. 发布前检查目标 JAR 名称、版本、hash、Git状态、changelog和凭证。

验收：用 mock server模拟 4xx、5xx、timeout和部分成功，脚本退出码与摘要准确。

### 工作包 0C：可见缺陷

1. 修复薛定谔的猫模型 parent。
2. 删除 `CreateBiotechClient` 的重复 cardboard box renderer注册。
3. 修复 `en_us` 中 28 个中文 Ghast Ponder文案；修复要回到生成源/合并流程。

验收：资源重载日志无本模组 missing model；英语客户端不再显示中文场景文本。

## 3. 阶段 1：建立安全网

建议周期：3–7 天。目标是让后续修复可验证、可回归。

### 工作包 1A：基础 CI

必跑：

```text
Java 17 / Windows
  -> compileJava
  -> unit test
  -> resource lint
  -> refmap verification
  -> build
  -> JAR contents/license check
```

可增加 Linux compile job检查路径大小写。CI 必须显式验证 test result非空，避免 `NO-SOURCE` 继续绿色。

### 工作包 1B：第一批测试

- 网络 decoder/authorization测试；
- `AllayAddressRules`、flight math、candidate normalize；
- publish.py 参数/失败语义；
- 资源 JSON重复键、model/texture、语言 CJK/placeholder、advancement parent；
- Mixin inventory/refmap关键 selector检查。

### 工作包 1C：诊断

- 建统一 `CreateBiotech.LOGGER`/once logger；
- SavedData 丢弃 entry必须打印有上下文的 warning；
- 兼容 fallback不再捕获无边界 `Throwable` 或静默忽略；
- `test.py` 保存日志、JAR hash、Java版本并提供 timeout/cleanup/成功条件。

验收：一个失败的 packet test、缺失 model、缺失 refmap entry、发布 500响应都能让 CI 明确失败并给出可定位证据。

## 4. 阶段 2：运行时稳定性与兼容

建议周期：1–2 周。目标是修复跨会话污染、dedicated server边界和 Mixin风险。

### 工作包 2A：生命周期

建立统一 lifecycle coordinator：

- server starting先 reset并绑定新 SavedData；
- server stopped discard visual entities并清空 Allay target/HUD/task引用；
- 清空 liquid slime source hits、BioPackager tracker；
- client level unload/disconnect清 Blast Chamber追踪与动画状态；
- weak contraption ref为 null时移除 tracker entry。

验收：同一 JVM依次进入世界 A/B，B 中不存在 A 的 target、HUD、实体引用、hit count或 animation state。

### 工作包 2B：全局实体热路径

把 liquid slime上一 tick状态改成 server-side transient、level-scoped state；不再为无关实体写 persistent NBT。补 100/500/1000 生物 profile。

### 工作包 2C：Mixin

1. 用 `@WrapMethod` + `try/finally` 修 damage context。
2. 按注解逐个修高风险 `remap = false`，每批运行 `compileJava` 并检查 refmap。
3. Fluid Tank renderer只捕获预期异常，原始 Create渲染错误保留根因。
4. JEI optional mixin保持 `@Pseudo`，为版本错配输出一次性 warning。
5. Mixin改动完成后运行有 timeout的 quickplay/smoke；普通非 Mixin改动不默认启动客户端。

### 工作包 2D：客户端类加载

- [x] 删除 Butter Cat 独立 `ModPartialModels`/module 初始化，模型声明和注册移到主客户端入口；
- [x] common block entity不再返回 `PartialModel`，由 renderer、visual 和 JEI 客户端映射模型；
- [ ] dedicated server smoke确认无 client-only class load。

验收：dedicated server可启动，required Mixin全部应用，集成服务器重开无跨世界状态。

## 5. 阶段 3：性能与数据结构

建议周期：1–3 周。目标是解决会随规模放大的算法问题；所有优化以 profiler为依据。

### 工作包 3A：Blast Chamber

1. 提取/缓存 `ChamberStructure` 拓扑：press、packager、vault、master press。
2. 方块变化把结构标记 dirty，取消 pending 状态每 tick全结构扫描。
3. 建 `UUID -> tracked state` 与 `packagerPos -> UUID`，空间查询只作为恢复 fallback。
4. 客户端每 tick只计算一次 master press和 positions。
5. 为 size 5/16/32、有效/末端错误结构记录 tick。

不要一开始就重写全部 3165 行 block entity；先把缓存和索引作为可测试组件抽出。

### 工作包 3B：便宜短路与索引

- Shulker Teleporter先检查 speed/address/target，再查询 entity；
- Allay target按 canonical address建立二级索引，取最优项不全量 sort；
- Allay target timeout cleanup从每 tick降到合理周期；
- Air Courier HUD按 player分桶 observation并用 source set；
- Experience Pump同 tick复用 orb/player snapshot，谨慎处理 simulate/execute。

### 工作包 3C：Belt 分配

- 无 passenger时不创建 removal list；
- profile Slime Belt四次 ordered list/sort；
- 小列表场景只复用 scratch collection，大列表场景再考虑一次排序分桶；
- 提取 Slime/Magma passenger helper，避免两套逻辑漂移。

验收：基准场景的 server/client tick和分配率有前后对比，且默认玩法时序与 NBT兼容不变。

## 6. 阶段 4：架构与生成治理

建议周期：2–6 周，可随功能开发渐进进行。

### 工作包 4A：Allay 模块边界

- 定义 `AllayModule.registerCommon/registerClient/registerEvents/registerPackets/reset`；
- `CB*` 成为唯一注册所有者；
- `All*` 若保留则明确为 compatibility facade，主体不得反向依赖；
- 把主体提供给 Allay 的 package/network/registry能力收敛成接口；
- Allay hard-coded config迁入真实 ForgeConfigSpec。

### 工作包 4B：大类提取

按风险顺序：

1. Blast Chamber：structure、processing、creeper lifecycle、client state；
2. Spider Assembly Table：inventory/fluid policy、slot lock、process plan；
3. belt：拓扑、切分/合并快照、surface/funnel兼容；
4. `CreateBiotechClient`：renderer、screen、model、reload、Ponder模块化。

每次提取保持 registry name、NBT key、packet id和 recipe id不变，并先写 characterization test。

### 工作包 4C：生成资源

- 决定真正启用 datagen还是删除空壳 runData说明；
- 若启用，把 `src/generated/resources` 接入 source set并做 regenerate-and-diff；
- Ponder生成 Java移到独立 generated source dir；
- 固定 exporter版本、源工程、命令和 post-export translation merge；
- 重建 `.ponderer-export/manifest.json`，校验 owned files、Java NBT引用和语言值。

### 工作包 4D：参考源与贡献治理

- 提交 `ref/SOURCES.md`/manifest与 bootstrap验证脚本；
- 把关键 Mixin/验证规则放入版本控制的 CONTRIBUTING/DEVELOPMENT；
- 修正 README 的 Registrate、datagen、tools、Mixin config和 bundled ref描述；
- 增加 CHANGELOG、RELEASING、SECURITY入口。

## 7. 快速收益清单

以下项目通常可以独立小 PR完成：

| 工作项 | 预估 | 风险 |
| --- | --- | --- |
| 修模型 parent | S | 低，需资源重载 |
| 删除重复 renderer注册 | S | 低 |
| Shulker config count分配前校验 | S | 低，需恶意输入测试 |
| Shulker Teleporter先短路再实体扫描 | S | 低，需行为测试 |
| Belt无 passenger时不分配列表 | S | 低 |
| server stopped清 static manager | S/M | 中，需跨世界测试 |
| JAR加入 license/notices | S | 低，需产物检查 |
| publish.py 失败非零退出 | S/M | 中，需 mock测试 |
| 统一 logger并记录 SavedData丢弃 | S/M | 低 |
| README 与 clean clone事实对齐 | S | 低 |

## 8. 不建议的实施方式

- 不要在没有测试时一次性重写 Blast Chamber、Spider Table或全部 belt。
- 不要批量删除所有 `remap = false`；按 target/descriptor逐项验证。
- 不要把 `defaultRequire` 全局改成 0来“提升兼容性”。
- 不要只在客户端 UI限制网络输入；所有规则必须在服务端重算。
- 不要继续手改 Ponder generated输出而不回写源/merge流程。
- 不要把 quickplay的“进程启动成功”当成“功能验证通过”。
- 不要为了减少小对象分配引入复杂池化；先修全局扫描、N²路径和生命周期。

## 9. 发布门禁建议

发布候选必须同时满足：

- Java 17 clean build；
- 单元测试、GameTest和资源 lint通过；
- required Mixin runtime smoke通过；
- JAR包含 license、third-party notices、两个 mixin config、refmap和预期资源；
- 同源码重复构建 hash一致，或差异有明确允许清单；
- dependency range与已验证矩阵一致；
- `latest.log` 无本模组 missing model/recipe/Mixin错误；
- publish dry-run/平台 API校验成功；
- Git工作区干净、版本/changelog/tag一致；
- 手动抽样：JEI有/无、Jade有/无、dedicated server、集成服务器换世界、关键 Ponder场景。

## 10. 完成定义

“优化完成”不意味着所有 P2/P3 都已实现，而是：

1. 所有 P0 已修复并有回归测试；
2. P1 已修复，或有明确 owner、issue、版本目标和临时防护；
3. CI 能阻止网络边界、资源、refmap、许可证和发布脚本回归；
4. clean clone可以按版本化文档复现构建与参考源；
5. 性能结论有 profiler数据而不只依赖静态推断；
6. 大型重构保持 NBT、registry、network和 recipe兼容。
