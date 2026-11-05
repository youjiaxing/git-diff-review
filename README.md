# GoLand Diff Navigation

在 GoLand 的 Git 暂存区 Diff 中跟随代码跳转，继续在同一个「提交」预览标签页审阅目标文件。

## 行为

- 从暂存区 Diff 开始审阅会话；经过未修改源码后，继续跳转到修改文件仍回到 Diff。
- 保留原生声明解析及多目标选择；单个查找用法结果的 EditSource／回车及结果树双击可以延续已有会话。
- 默认跟随本地目标：有未暂存修改时进入 INDEX ↔ 本地，否则进入 HEAD ↔ INDEX；也可设置为只审已暂存。
- 复用当前提交预览、同步提交树选中项，保留 IDE 原生的暂存、取消暂存、还原控件。
- 通过现有 Back / Forward 键位恢复源码与 Diff 混合路径，包括侧别和光标；内容变化不会清空整条历史。
- 无修改文件使用普通源码；未参加会话的普通导航、冲突视图、显式分屏及不支持的视图保留原生行为。
- 源码标签不会被自动关闭；只有显式点击 Stage／Unstage 选中行才写入 Git 暂存区，不执行 commit 或改写工作区。
- 状态栏 **Diff Review: On / Idle / Off** 显示会话状态；点击可暂停审阅、查看最近导航原因或打开设置。
- Diff／源码右键 **Start / Pause Diff Review** 控制会话；Diff 右键 **Open Review File as Source** 单次打开源码并保留返回路径。
- **设置 → 版本控制 → GoLand Diff Navigation**：持久化项目启用偏好、自动开始和目标比较范围。会话及历史不跨重启恢复。
- 手动切到无关源码后不继续接管；关闭所属 Diff 预览会释放会话。

## 按行暂存与取消暂存

在暂存区文本 Diff 中鼠标选中单行或连续多行，附近会出现 **Stage 选中行**／**Unstage 选中行** 浮动操作条；也可使用 Diff 右键菜单 **Stage / Unstage 选中行**，并在 Keymap 为该动作绑定快捷键。操作由当前比较版本决定，不固定按屏幕左右判断。旧版本侧可选择删除行。新增／删除文件使用的原生单栏文本视图也支持。

明确的修改直接执行。部分替换关系无法唯一确定时，展示当前 INDEX 与候选结果的差异；右侧可编辑，确认才写入，取消不修改。没有选区、只选未修改内容或多个不连续选区不会写入，也不会扩大为整个区块。

仅更新对应文件的暂存内容，保留工作区及无关已暂存修改。写入采用 Git index 锁，在锁内校验基线并原子发布；文件、HEAD、INDEX 或文档在最终校验前变化时取消，不强制覆盖。不承诺跨 HEAD、工作区及 INDEX 的联合事务，最终校验后的源文件变化作为后续修改显示。

保留已有文件的编码、BOM 与未选行的原始换行格式；新增文件完整 Unstage 后移除 INDEX 条目、本地文件保留；工作区清空文件与删除文件分别保留空条目或移除条目。仅编辑候选后无法确认混合换行重复行的身份时拒绝写入。

Stage 前本地编辑需已保存，插件不自动保存源码。冲突、重命名、符号链接、子模块、二进制和纯元数据变更使用原生操作；大文件或无法安全处理的候选会说明原因。

## 标签定位提交面板

提交面板已显示时，切换源码标签会选中并滚动到对应文件；普通源码优先未暂存项，同文件已选中另一个分组时保持；Diff 按当前实际比较分组定位。已有多选或后续手动选树优先，过期的异步定位不能覆盖手动选择。

不打开隐藏面板、不改变过滤、不切换源码到 Diff、不抢焦点；文件不在当前树中时保留原选区。此定位独立于审阅会话，不执行 Git 写入。原生后台预览可能随树更新，返回已有审阅预览时会恢复之前位置，并保留审阅历史。

## 兼容范围

首版适配 **GoLand 2026.2，build 262**，开发 SDK 为 **2026.2.2.1 / GO-262.10315.160**，需要 JDK 25 构建。

目前接管提交工具窗口的**双栏 Git Staging Diff**。Unified、Combined、三栏、历史提交及普通单文件比较使用原生导航。内部 API 有版本约束，因此插件将兼容版本限定在 `262.*`。

INDEX 文件与工作区内容相同时，声明解析会使用工作区 PSI 上下文。内容不同时保留原生解析，仍不保证 INDEX 中的声明解析能成功。目标定位使用可跟随编辑的位置标记；跨文档仅接受来源、目标均无歧义的上下文或符号整行匹配，不能确认时打开源码而不使用旧偏移。固定只审已暂存时，目标没有可用 INDEX 或无法确认对应位置，也使用源码。

同项目会话按预览保存历史；多窗口／分屏交互尚未完整验证。用法适配目前限单个 `UsageInfo2UsageAdapter` 结果和结果树，批量打开及其他用法弹窗路径保留原生行为。

## 本地构建

无需网络下载构建依赖，直接使用已安装的 GoLand SDK：

```powershell
.\scripts\build-local.ps1 `
  -IdePath 'C:\path\to\GoLand' `
  -JdkPath 'C:\path\to\jdk-25'
```

输出 `build/local/goland-diff-navigation-0.3.0.zip`。脚本会先运行历史、选中行变换及独立临时 Git 仓库的事务检查。

标准 Gradle 构建使用 [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)：

```powershell
# JAVA_HOME 指向 JDK 25
.\gradlew.bat buildPlugin -PlocalIdePath='C:\path\to\GoLand'
# 省略 localIdePath 时下载 gradle.properties 指定的 SDK
```

Gradle 输出在 `build/distributions/`。首次运行需要访问 Gradle、JetBrains 和 Maven 仓库；本地 SDK 脚本可用于这些服务不可访问时。

## 验证

`src/test` 包含无需第三方依赖的历史回归检查，覆盖连续跳转、停留位置、后退后分叉、失败不移动游标以及容量上限。

`src/smoke` 是单独打包的 IDE 集成测试插件，**不包含在正式安装包内**：

```powershell
.\scripts\start-sandbox.ps1 -IdePath 'C:\path\to\GoLand' -JdkPath 'C:\path\to\jdk-25'
```

它会在 `build/sandbox/` 下创建测试 Git 仓库、独立 IDE 配置和测试插件，打开另一个 GoLand 实例，运行原生声明跳转与审阅历史检查。报告位于 `build/sandbox/smoke-report.txt`。关闭该测试实例后，可用 `-Reuse` 重跑；不会复制主 IDE 的配置或账户数据。

## 安装与卸载

GoLand → 设置 → 插件 → 齿轮 → 从磁盘安装插件，选择生成的 ZIP，并按 IDE 提示重启。随后在「提交」工具窗口的未暂存文件上打开 Diff，即可使用原有代码跳转操作。

本机已安装旧版时，也可运行 `scripts/install-local.ps1 -IdePath ... -JdkPath ...`：备份旧版并使用 IDE 原生启动更新队列安排升级，不结束当前 IDE。`-VerifyOnly` 在 `build/` 隔离目录验证更新，不修改日常安装。

卸载：设置 → 插件 → GoLand Diff Navigation → 卸载。审阅历史仅保存在内存中。

## 实现入口

- `StageNavigationHandler`：在原生导航最终打开源码之前接收 `SourceNavigationRequest`。
- `StageNavigationService`：选择暂存区目标、同步预览、加载后定位、取消失效操作。
- `IndexDeclarationHandler`：为内容相同的 INDEX 快照补充工作区解析上下文。
- `ReviewHistory`：保存同一预览内的文件、分组、侧别、位置及前进／后退路径。
- `ReviewHistoryAction` / `ReviewActionListener`：沿用键位并接入可用的原生导航按钮。

源码和构建产物不包含业务项目代码、IDE 二进制或账户凭据。
