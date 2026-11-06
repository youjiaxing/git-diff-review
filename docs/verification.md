# 验证记录

环境：Windows，GoLand 2026.2.2.1 / GO-262.10315.160，JDK 25。

## 已完成

### 0.3.2 新增文件审阅与提交树定位

- 修复前，`build/sandbox-added-red/smoke-report.txt` 记录原生声明跳转从未跟踪 A 的单栏 Diff 打开 B 源码，以及单选多文件目录／单文件目录后未定位到目标文件的三项失败。
- 新增项目级 `openNewFilesInDiff` 偏好，默认开启，涵盖未跟踪、全部暂存和部分暂存的新文件。关闭后的源码导航保留会话；应用设置仍按既有规则清空会话。新增 XML 缺字段默认值及显式 false 序列化往返检查。
- 提交树按实际选中路径数判断多选，并以直接选中的文件节点判断是否已定位；不将目录后代当成用户多选或精确选中文件。
- 补齐原生新增文件单栏 Diff 的会话、位置、版本校验、导航与历史；单栏 INDEX 的声明解析仍限与本地内容相同的情形。目标位置有歧义时仍回退源码。
- 用户指出 smoke IDE 的 `Write-unsafe context` 日志后，确认旧测试通过数仅代表功能断言。测试驱动改用 IDE 写安全事务；正式导航的 Back/Forward、EditSource 监听器也改为退出动作前置回调后执行，避免打开编辑器时在禁止派发 AWT 事件的回调内泵事件；延迟导航失败仍回退原生动作。
- 测试插件新增最终日志检查，出现 ERROR／SEVERE 不得报告完整通过；测试插件不进入正式安装包。
- 最终独立实例 `build/sandbox-added-v032-final-safe/smoke-report.txt`：`PASS 106 integration checks`；对应 `log/idea.log` 的 ERROR／SEVERE 计数为 0。包括原生单栏未跟踪 A→B、单双栏互跳、INDEX 新增文件声明解析、两种目标范围、三种新增状态的开关行为、暂存／完整取消暂存后的 Back、真正目录多选保护和精确文件定位。
- 本地 SDK 构建通过：15 项历史、14 项选区变换、20 项真实 Git 事务检查。正式 0.3.2 ZIP 的 ID、版本、结构及不含 smoke/test 类已核对。
- 测试驱动仅在独立 sandbox 使用本机 SDK 的 `TransactionGuardImpl.wrapLaterInvocation`，进入包装前在 EDT 检查实际模态状态、项目生命周期及重入；包装恢复写安全上下文，tick 保留写安全断言。该 SDK 内部测试依赖不进入正式包，未声称其他 SDK 可用。
- 独立实现审查和日志问题定向复核已完成。安装迁移的 `-VerifyOnly` 隔离模拟通过，保留新旧安装目录迁移能力。
- 日常 GoLand 的下次启动队列已更新为 0.3.2，旧版和原队列备份于 `build/install-v032/backup-20261106-122853`。独立测试实例已关闭，日常 IDE 未重启；0.3.2 实际加载仍待用户重启后确认。

### 0.3.1 项目与插件重命名

- 本地项目目录、构建名、安装包、插件显示名和插件 ID 统一为 Git Diff Review / `git-diff-review`；Java 包和既有 action ID 保留。
- 本地 SDK 构建通过：15 项历史、14 项选区变换、20 项真实临时 Git 仓库事务检查。
- 新 ID 的正式插件和 smoke 插件在独立 GoLand 配置中加载，`build/sandbox-rename-v031/smoke-report.txt` 为 `PASS 69 integration checks`；测试实例已关闭。
- 正式 ZIP 核验：只有 `git-diff-review/lib/git-diff-review-0.3.1.jar`，描述符 ID、显示名及版本正确，不包含 smoke 或测试类。
- `install-local.ps1 -VerifyOnly` 验证同时存在新旧安装目录时，启动更新后仅留下新目录与 0.3.1 JAR。
- 独立审查发现复用 sandbox 会残留旧 ID；已增加仅在项目 `build/` 内清理新旧两个插件目录的路径检查和处理。该修正经代码核查，未再次启动完整集成测试。
- 日常 GoLand 已安排下次启动迁移，已备份旧版；本次未关闭或重启日常 IDE。实际重启后加载新 ID 尚待确认，旧项目偏好不自动迁移。
- 名称通用化没有扩大已验证的 IDE 范围，仍限本页所列 GoLand 262 环境。

### 0.3.0 按行暂存与标签定位

- 本地 SDK 编译及 15 项历史、14 项选区变换检查通过。
- 20 项真实临时 Git 仓库检查通过：INDEX 原子写入、其他暂存文件和工作区保留、过期基线拒绝、他人 index.lock 保留、失败清锁、新文件/空文件/删除条目、原始及混合 CRLF、BOM 独立状态、重复行来源映射、恢复 HEAD 格式。
- `build/sandbox-v030-final/smoke-report.txt`：`PASS 69 integration checks`。在独立 GoLand 进程运行，包括原 42 项导航与历史场景、实际 SDK 空文档/EOF fragments、选区浮窗按钮、单行 Stage/Unstage、歧义替换预览确认/取消、新文件无末尾换行、删除行左右侧操作，以及操作后 Diff 和提交树刷新。
- 标签定位验证：源码保持活动、未暂存优先、同文件另一分组保持、多选不受影响、下一次切标签恢复、无匹配保留选区、手动选择取消排队定位；返回被动跟随后台变化的预览时恢复原文件，并保持原 Back 历史。
- 两名隔离 Reviewer 分别检查数据写入和界面/历史联动；发现的格式、EOF、旧 parked 恢复及被动恢复误开源码问题已修正并定向复核。
- 测试配置禁用首次启动的引导及 AI 推广弹窗，只影响独立 sandbox 配置。测试中的真实 Git 写入只发生在生成的临时仓库和 `build/` fixture。

边界：浮窗由测试直接触发选区 UI 后点击真实 Swing 按钮，预览通过真实对话框确认／取消；尚未逐一验证物理鼠标拖选。复杂编码/自定义 Git filters、多窗口所有布局和高负载竞争不宣称穷尽验证。未保存的本地文档先要求用户保存，插件不自动保存；编辑后的预览若无法安全保留混合换行重复行，会拒绝写入。

### 0.2.0 场景回归

- 修复前，新增的混合路径用例在 `sandbox-session-red` 稳定失败：`review session lost after unchanged source: expected B staging Diff, got ordinary B source`。
- 本地 SDK 编译通过；历史回归检查增至 15 项，包含失效节点移除、目标重新映射和位置资源释放。
- 独立实例验证源码与 Diff 的 Back／Forward、单个真实 UsageView 结果的 EditSource 及结果树双击、暂停后普通导航、设置页初始化。
- `build/sandbox-session-v020/smoke-report.txt`：`PASS 42 integration checks`。包含手动切换当前文件暂存分组后，Back／Forward 保留历史并记住最新分组；这不等同于穷尽实际 Git 暂存写入的异步刷新顺序。
- 部分暂存文件验证：只审已暂存时把本地符号映射到 INDEX 正确行；单次打开源码映射回本地并可返回；未暂存目标在固定暂存范围下使用源码；跟随目标模式打开未暂存 Diff。
- IDE 内位置检查验证插入前文、删除符号、目标重复上下文及来源重复上下文，歧义时拒绝旧坐标。
- 独立静态审查发现的来源匹配歧义、加载中后退未取消及分组变化丢历史均已修正并定向复核；原尝试的 Usage wrapper 已移除，避免丢失原生 options 后二次接管。

### 0.1.0 基线记录

- `scripts/build-local.ps1` 使用本机 GoLand SDK 编译生产代码、测试代码并生成安装 ZIP。
- `ReviewHistoryTest`：10 项历史回归断言通过。
- `SmokeActivity` 在另一个 GoLand 进程中加载正式插件和独立测试插件：17 项集成断言通过。
- 独立实现审查发现的三处问题已修复，随后定向复核未发现有充分证据的剩余缺陷。

集成检查使用原生 `GotoDeclaration` action 验证：

1. 未暂存 `main.go` → 仅已暂存 `b.go`，定位函数声明。
2. 从 INDEX 中的 `b.go` 继续跳到未暂存 `c.go`。
3. Back / Back / Forward / Forward 恢复同一提交预览中的文件和位置。
4. 上述跳转期间不新增普通源码标签。
5. 无修改的 `clean.go` 使用普通源码编辑器，同时保留提交预览。
6. 从普通源码编辑器继续导航时，仍使用普通源码标签（0.1.0 行为，0.2.0 改为由审阅会话决定）。
7. 已经打开的普通源码标签不会被关闭。

部分暂存历史另外用最终 `SourceNavigationRequest` 验证：从 INDEX 与工作区内容不同的文件出发，跳到另一个 Diff 后 Back，返回原 INDEX 的侧别、内容和坐标；没有将工作区新增的行号套到 INDEX 上。

测试源码、断言与测试仓库生成方式保留于 `src/smoke/` 和 `scripts/start-sandbox.ps1`，测试插件不会进入正式 ZIP。0.1.0 基线报告包含 `PASS 17 integration checks`，0.3.0 的当前完整报告为 69 项。生产安装包只包含 `src/main`。

## 验证边界

- 用法树双击通过 IDE 事件队列派发真实 MouseEvent，EditSource 通过 ActionSystem；尚未使用物理鼠标或键盘输入。其他用法弹窗、多结果批量打开不宣称支持。
- 同项目多窗口、并发加载时焦点变化、Git 外部改名／删除、实际暂存写入后的所有刷新顺序，以及大文件性能尚未完成端到端验证。
- 配置使用平台 PersistentStateComponent 保存项目偏好；重启后的磁盘恢复尚未单独做集成断言。

- 原生 action 是通过 IDE ActionSystem 触发；尚未单独做物理鼠标 Ctrl+Click 输入验证。鼠标路径和候选弹窗仍由 IDE 原生处理。
- Unified、Combined、三栏冲突、分屏与历史提交不在首版接管范围内。
- INDEX 与本地内容不同的声明解析仍由 GoLand 原生处理，首版只在两者相等时补充工作区 PSI 上下文。
- IDE 当前通过的 `GotoDeclarationAction.findAllTargetElements` 在该 SDK 标有 `TestOnly`，导航扩展和暂存区接口也包含实验／内部 API。插件将版本锁在 `262.*`，升级主版本需重新适配和验证。
- `gradlew buildPlugin` 因本机访问 Gradle 插件仓库时 TLS 握手失败，未完成标准 Gradle 构建验证；本地 SDK 脚本已完成实际编译、测试和打包。
- GitHub Actions 仅设置手动触发；尚未在远端运行。

## 本地操作隔离

测试仓库、账户无关 IDE 配置和插件都在 `build/` 内；未读取或修改业务仓库的 Git index，也未将测试工具装进日常 IDE。
