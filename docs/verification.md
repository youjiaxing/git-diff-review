# 验证记录

环境：Windows，GoLand 2026.2.2.1 / GO-262.10315.160，JDK 25。

## 已完成

### 0.3.7–0.3.9 GitHub 构建与正式版本自动发布

- `gradle.properties` 的 `pluginVersion` 成为唯一版本来源。Gradle 注入描述符版本，本地打包生成版本化资源，安装脚本及安装队列验证 helper 读取同一版本；不再逐处替换硬编码版本。本地构建 15 项历史、19 项选区变换、24 项真实 Git 事务检查及 0.3.7 升级隔离模拟通过。
- 发布脚本的 27 项检查覆盖真实临时 Git 历史和隔离 API 模拟：累计未发布变更、文档豁免、版本回退、版本／标签冲突、ZIP 及生产描述符、测试代码泄漏、原始摘要、完整草稿后公开、部分上传恢复、公开资产不覆盖、同 SHA 重跑及 Latest 最高版本重算；新增首次验证记录的原 SHA／artifact ID 绑定、产物变化拒绝及版本公开后的记录失效。
- 标准远端 Gradle 构建首次实际跑通。最终 [Actions run 42972054181](https://github.com/youjiaxing/git-diff-review/actions/runs/42972054181) 对提交 `8ee7396e59d9e8250721dd0d9e92e654066784f8` 完成 Windows／Java 25 构建、27 项发布规则检查、三类 Java 回归及安装 ZIP 校验。此证据取代此前“标准 Gradle 因本机 TLS 未完成”的当前构建缺口，历史记录仍保留原发生条件。
- 远端原始 artifact `13834200751` 中的 `git-diff-review-0.3.7.zip` 为 144835 字节，SHA-256 为 `f1d65917002975f331ff7748816b08b7eba7aa98d0312d749b8ab164eb8f8c79`；通过 GitHub 连接应用下载，安装包与 manifest、校验文件一致。`start-sandbox.ps1 -PluginArchive` 直接安装该 ZIP，不重新打包；独立 GoLand 日志已确认实际加载 `Git Diff Review (0.3.7)`。
- 使用该远端 ZIP 的 `build/sandbox-release-v037/smoke-report.txt` 为 `PASS 189 integration checks`，平台 ERROR／SEVERE 为 0。该结果仅属于本次安装产物验证，不代表未来每次云端 CI 都运行 IDE 或物理鼠标测试。
- CI 发布以目标提交祖先中的成功正式版本为基线，PR／非 main 不公开发布，失败不公开不完整资产。同版本的公开标签与安装包不覆盖；已完成重跑核验原资产，草稿恢复使用原 attempt 的成功 build 与固定 artifact。构建 job 只读，发布 job 使用 `contents: write`，跨 run 下载使用 `actions: read`。不同正式版本独立处理，Latest 串行重新查询最高正式版本。
- 本机 CLI 的 Actions 产物下载及 workflow_dispatch 返回 403；GitHub 连接应用能下载。首次发布因此通过主干中的隔离验证记录及门禁配置推送启动，仍复用上面的确切 SHA／ZIP／摘要，未引入新凭据或放宽来源检查。IDE 兼容性仍只实测 GoLand 262，Marketplace 保持暂缓，日常 IDE 未被重启。
- 首次开放门禁的 [run 42972813365](https://github.com/youjiaxing/git-diff-review/actions/runs/42972813365) 在上传资产时返回 `Bad Content-Length (HTTP 400)`，仅留下 0.3.7 的受控草稿，未公开缺包版本。上传改为使用真实临时文件及 `gh release upload`，不覆盖已有资产。
- 0.3.8 的 [run 42973523087](https://github.com/youjiaxing/git-diff-review/actions/runs/42973523087) 已成功构建并公开 0.3.8，但恢复旧草稿时向 Actions artifact 下载端点发送了只适用于 Release 资产的请求头，返回 HTTP 415，整次运行结论为失败。0.3.9 已区分这两类下载端点，并补充对应回归；发布规则检查共 32 项通过。
- 0.3.7 已在后续运行中从原构建恢复公开，公开 ZIP 的 SHA-256 与上述隔离验证原包完全一致；0.3.8 公开 ZIP 为 144835 字节，SHA-256 为 `785e130ad7ad4bbbb59ec58b4af30addc39239672d6339c2a39e07e4d0748bd9`。两个版本的公开 ZIP、校验文件和 manifest 均已重新下载核验，标签分别指向 `8ee7396e59d9e8250721dd0d9e92e654066784f8` 和 `b6a653029291a2d2f4d991ab12b150048e6198f5`。0.3.7 较晚恢复公开未将 Latest 从 0.3.8 回退。
- 最终 [run 42973852121](https://github.com/youjiaxing/git-diff-review/actions/runs/42973852121) 的 build、publish、recover、latest 四个 job 全部成功。0.3.9 远端通过 32 项发布规则、15 项历史、19 项选区变换和 24 项真实 Git 事务检查；[正式 Release](https://github.com/youjiaxing/git-diff-review/releases/tag/v0.3.9) 已公开且为 Latest，标签指向 `f30d1db985ba25777835a3771ba85b7bb6a16fca`。从公开地址重新下载的安装 ZIP 为 144835 字节，SHA-256 为 `8b6e619b99a367912e663271ad0989a9e163113b97b44f5332e734b8a8d553ef`，已通过 `release.py verify` 的 ZIP、校验文件、manifest 及提交身份核验。
- 0.3.8／0.3.9 是发布基础设施修复版本；完整 GoLand 189 项集成及加载证据属于上面的确切 0.3.7 原包，不声称对 0.3.9 重新执行了完整 IDE 集成。未向 Marketplace 发布，也未更新或重启日常 IDE。

### 0.3.6 选区有效动作与 Discard

- 选行操作统一在后台读取 HEAD、INDEX、LOCAL 并生成候选，浮窗、右键和快捷键使用同一份资格结果。INDEX ↔ LOCAL 左侧的 Unstage 单独对照 HEAD；未暂存删除仍可 Stage／Discard，纯上下文无可执行动作。原合并快捷动作遇到 Stage／Unstage 同时有效时明确选择，不执行隐式 Discard。不同侧不能复用旧选区计划，后台动作更新不抢走另一侧的选区归属。
- Discard 仅修改真实 LOCAL Document，包含未保存编辑，以 INDEX 为基线，不写暂存区、不主动保存、不恢复整份已删除文件。每次独立命名撤销命令，支持原生 Undo／Redo；部分替换先确认只读预览，不能编辑全文扩大范围。纯未跟踪文件清空后保留空文件。已有删除侧 Stage／Unstage 保留。
- SDK 原生 Undo 依据侧编辑器的 DataContext 工作，不能把整个 DiffFileEditor 或另一个源码标签当作相同上下文。右侧原生 Undo／Redo直接使用平台历史；INDEX 左侧新增窄范围 UndoManagerProvider，仅当当前未暂存双栏的真实 LOCAL 下一项是本插件 Discard 时委托右侧 TextEditor，其他上下文返回 null，不修改 INDEX、不覆盖原生动作。
- 选区、文档、窗口、请求及导航变化使候选失效，确认后再复核；插件跨预览／项目的写入按 INDEX 路径互斥。Discard 后台只读复核 Git 基线，写命令内复核真实文档；不承诺与外部 Git／编辑器写入的联合原子事务。HEAD 内容按捕获的 OID 读取，合法 unborn HEAD 与读取失败区分；捕获时确认真实仓库根，拒绝损坏的嵌套仓库回落到父仓库。
- 暂存写入后刷新最新基线；完全 Unstage 新文件会自动回到未跟踪预览。Discard 文档变更、撤销和重做触发原生 rediff，保留可确认的操作侧及位置。取消输入监听随 viewer 重建重新绑定，并覆盖编辑区、滚动容器和滚动条；用户后续输入优先。
- 精确复现报告 `build/sandbox-selection-red/smoke-report.txt`：旧版在 INDEX 左侧选中已暂存新增行后没有 Unstage。独立实现审查发现的跨侧缓存及新 viewer 输入监听缺口均已修正，并加入对应回归。
- 最终独立实例 `build/sandbox-selection-v036-final2/smoke-report.txt`：`PASS 189 integration checks`，平台 ERROR／SEVERE 为 0。新增覆盖选区动作及混合选择、左右侧原生撤销／重做、连续独立撤销、未保存文档及未选编辑保留、不自动保存、删除行恢复、纯新文件清空及无末尾换行恢复、歧义 Discard 取消／确认、改选取消、UNSTAGED→UNTRACKED，以及 viewer 更换后的滚动取消；保留此前菜单、导航、历史、删除和连续暂存检查。
- 测试通过 SDK ActionSystem、真实 Swing 按钮／对话框、编辑器和 UndoManagerProvider 执行。Windows Computer Use 在刷新窗口后两次截图分别返回 `FrameArrived timed out` 和 `window capture timed out`，备用 CUA 同样超时；可访问性仅暴露系统窗口栏。因此未完成实际鼠标拖选及视觉体验验证，不能将自动化通过数视为这部分验证已完成。
- 本地 SDK 构建检查：15 项历史、19 项选区变换、24 项真实 Git 事务。纯读取复核不创建 index.lock、不修改 INDEX；过期 INDEX、非法 HEAD 与合法 unborn HEAD 均有独立检查。仍仅验证 GoLand 262，源码光标接续到 Diff 不在本版本实施范围。
- 正式 0.3.6 ZIP 已核对插件 ID、版本、单一生产 JAR、Undo provider 声明及不含 smoke／测试类；本机升级隔离模拟通过。已安排日常 GoLand 下次启动加载 0.3.6，旧版及原队列备份于 `build/install-v036/backup-20261111-122518`；独立测试实例已关闭，日常 IDE 未重启，实际加载待用户确认。

### 0.3.5 新增文件菜单与连续暂存刷新

- 纯未跟踪文件可通过原有 `Git.Stage.Compare.Local.Staged` 菜单进入同窗口原生“空版本 ↔ 本地”提交预览；打开预览不创建 index 条目。仍保留普通源码资格、原生暂存区可用性和项目启用偏好，其他比较动作不接管，显式入口不受自动导航的新增文件偏好限制。
- 新增 `CompareWithStagedAction` 声明式同 ID 覆盖，先委托原动作的启用逻辑，仅为支持的未跟踪源码扩展启用。原动作可能仍为 `ActionStub`，因此延迟取得 base，使用 SDK 无状态原生类及 `stub.initAction` 保留原有本地化文本与快捷键信息。声明式卸载由平台恢复 base；没有使用会残留 wrapper/base 注册记录的程序化反向 `replaceAction`。
- `StageRefreshService` 在选行事务开始前记录所属预览和取消监听，写入成功后等待状态与树模型，按完整路径重新选择 UNSTAGED，并强制重新加载请求、核对实际 INDEX 文本。后续同类型 Stage 同样绕过请求缓存。旧视图被标为过期，等待基线确认期间不再允许按行写入；刷新超时仍保留真实写入结果并记录原因。删除文件没有本地侧，保留原生刷新路径。
- 用户切标签、手动树输入、动作导航、关闭预览或应用设置会终止该次自动重选；切走再切回不复活。显式重新打开已取消刷新的预览会重新加载旧请求，能够再次按行操作。纯未跟踪预览打开失败后，仅当请求仍拥有当前窗口才恢复原源码；已跟踪文件维持原生 fallback。
- 初始复现 `build/probe-newfile-refresh/report.txt`：真实 index 仅包含所选 `second` 行，工作区未变，但预览仍为 UNTRACKED 单栏；手动重选 UNSTAGED 后左侧正确显示 index。`build/sandbox-newfile-menu-red/smoke-report.txt` 记录旧版纯新文件菜单不可用。
- 第一轮完整回归发现 AsyncChangesTree 的完成回调虽在 EDT，仍可能是写不安全上下文；强制重载触发原生暂存请求解绑检查。已在回调后通过 `invokeLater(...nonModal)` 执行预览更新，最终日志无该错误。独立实现审查发现删除侧不适合新协调流程，已按本地文件存在性分流并跑删除 Stage／Unstage 回归。
- 用户指出隔离测试中出现“内容已变化，请重新选择”模态提示后，补充立即切换预览的竞争场景；过期操作安静取消，异步失败记录在状态栏 tooltip／诊断原因中，不再以模态信息框阻断后续操作。未保存源码等执行前提示和歧义结果确认不受此项调整影响。
- 最终独立实例 `build/sandbox-newfile-v035-nonmodal/smoke-report.txt`：`PASS 168 integration checks`；对应 `log/idea.log` ERROR／SEVERE 计数为 0。新增覆盖原生菜单 update／执行、预览打开前 index 无条目、部分 Stage 单栏到双栏、连续 Stage 最新左侧内容、等待期间禁止旧选区再写、工作区和无关 index 保留、禁用插件、自动开始／新增文件偏好关闭、切走／切回、显式重开恢复可操作、手动树输入以及切换预览时无阻塞信息框；保留已有菜单、分屏、导航、历史、删除和新增文件回归。
- 独立生命周期探针 `build/probe-action-lifecycle-safe/report.txt` 实际调用平台插件卸载：覆盖动作正确委托 Git 原生动作，卸载成功后同 ID 恢复 `GitStageCompareLocalWithStagedAction`，探针日志 ERROR／SEVERE 为 0。首次探针直接在 EDT 调用仅允许后台的校验 API 产生测试代码日志；修正为平台自身卸载入口后重新通过。该探针只位于 `build/`，不进入安装包。未声称多个第三方插件同时覆盖同一 ID 的任意卸载顺序均已验证。
- 本地 SDK 构建通过：15 项历史、14 项选区变换、20 项真实 Git 事务检查。正式 0.3.5 ZIP 的插件 ID、版本、单一 JAR 结构、覆盖声明及不含 smoke/test 类已核对；安装升级模拟通过。真实菜单通过 ActionSystem、选区及树输入通过测试驱动触发，没有声称逐项物理鼠标验证。
- 日常 GoLand 已安排下次启动更新为 0.3.5，旧版及原队列备份于 `build/install-v035/backup-20261111-103541`。本轮独立测试实例已关闭，日常 IDE 未重启；实际加载待用户重启后确认。

### 0.3.4 源码比较进入提交审阅预览

- 普通本地源码的原生 `Git.Stage.Compare.Local.Staged` 菜单优先使用提交面板拥有的预览，按完整路径精确选择 UNSTAGED，比较 INDEX ↔ LOCAL。显式打开可显示隐藏面板、替换多选，并在发起命令的编辑窗口中打开或复用预览；保留源码标签，不强制改写范围、自动开始、暂停或新增文件导航偏好。
- 新增 `SourceCompareService`，在动作前置回调只捕获资格和请求，退出回调后进入非模态写安全上下文执行 UI 操作。请求覆盖面板初始化、树刷新、预览创建和实际文本请求加载；取消与失败分开，过期请求不再执行原生回退。目标不可用或打开失败时回原生比较，光标无法映射时保留已确认的提交 Diff。
- 修复前 `build/sandbox-compare-red/smoke-report.txt` 记录真实菜单 Action 打开独立 Diff 的失败；菜单回归先等待原生 Git 状态可用，再通过 ActionSystem 调用。测试使用选中源码编辑器的真实文件、编辑器及项目上下文，没有直接调用插件比较实现替代原生入口。
- 集成迭代确认 SDK 262 的标签选中通知可能延迟到后续命令才到达，取消判断使用实际编辑窗口，避免旧通知吞掉新请求；树首次显示但目标尚未出现时有界等待。既有选区保留断言改为比较路径内容，允许树模型刷新后节点对象更换。
- 独立实现审查发现打开失败返回空编辑器集合时可能漏掉原生回退，已改为按实际预览选中状态判断；源码仍选中时能进入失败回退路径。此空集合分支经代码与 SDK API 核查，未注入平台故障进行端到端验证。
- 最终独立实例 `build/sandbox-compare-v034-final/smoke-report.txt`：`PASS 153 integration checks`；对应 `log/idea.log` 的 ERROR／SEVERE 计数为 0。新增覆盖冷启动隐藏 Commit、复用已有预览、INDEX／LOCAL 内容、从该入口选一行 Stage 后真实 index 仅变该行、工作区与无关 index 保留、禁用／仅已暂存／重命名原生回退、其他比较菜单、完整路径区分同名文件、快速连续命令、延迟执行前切源码／手动树输入、替换多选及 parked 预览、自动开始关闭／暂停保留、无法映射光标、未保存文档及新增文件偏好关闭。
- 单独的实际分屏检查确认预览在发起命令的 split 中创建对应 processor，另一 split 的既有提交预览仍保留；这不代表穷尽多窗口、分离窗口及所有焦点竞争。菜单和树输入由测试通过 ActionSystem／Swing listener 触发，未宣称物理鼠标菜单操作已逐项验证。
- 本地 SDK 构建通过：15 项历史、14 项选区变换、20 项真实 Git 事务检查。正式 0.3.4 ZIP 的插件 ID／版本、单一 JAR 结构及不含 smoke/test 类已核对；`install-local.ps1 -VerifyOnly` 升级模拟通过。
- 日常 GoLand 已安排下次启动更新为 0.3.4，旧版及原队列备份于 `build/install-v034/backup-20261110-103555`。独立测试实例已关闭，日常 IDE 未重启；新版实际加载待用户重启后确认。

### 0.3.3 默认仅审未暂存

- 默认范围的代码跳转和 Back／Forward 仅查找未暂存／未跟踪 Diff；只有已暂存改动的目标打开源码并保留会话。源码回退保留自动审阅意图，全部暂存后返回源码、取消暂存后再次返回可以恢复未暂存 Diff；显式单次源码和新增文件偏好关闭的源码意图独立保留。
- 普通源码标签只定位提交树未暂存／未跟踪项，不保留同文件的旧已暂存选区；无符合项时保留原选区。实际多选、后续手动选树和手动已暂存 Diff 的实际分组定位仍保留。
- 用户显式选回既有 Diff 预览时仍精确恢复原文件／分组；原分组消失则尝试当前范围内的 Diff，无可用项则打开原文件源码，不停留在后台跟随带走的无关文件。该显式恢复与默认历史返回范围分开处理。
- `FOLLOW_TARGET` 保留枚举身份、显示改为「只审未暂存修改」；实测本机 SDK 旧序列化会使用中文 `toString()`，因此增加转换器兼容旧中文值、新显示值及稳定 ID，今后写稳定 ID。主动保存的 `STAGED_ONLY` 及其他偏好不重置。
- `build/sandbox-unstaged-red/smoke-report.txt` 记录修复前仅已暂存源码自动选树和原生声明导航进入已暂存 Diff 的失败。新增真实 Git 暂存／取消暂存循环、源码意图、手动已暂存预览恢复及配置兼容回归；旧回归按未暂存默认规则调整，同时保留显式已暂存模式、INDEX 解析和按行暂存覆盖。
- 独立实现审查及配置转换定向复核已完成，未发现未解决的实质问题。
- 最终独立实例 `build/sandbox-unstaged-v033-manual/smoke-report.txt`：`PASS 128 integration checks`；对应 `log/idea.log` 的 ERROR／SEVERE 计数为 0。包含暂存→Back 源码→Forward→取消暂存→Back 未暂存 Diff 的完整循环、手动已暂存起点的默认历史重算、parked 原分组消失两种回退、显式源码例外和五种配置编码往返。
- 本地 SDK 构建通过：15 项历史、14 项选区变换、20 项真实 Git 事务检查。0.3.3 正式 ZIP 的插件 ID／版本、目录结构、配置转换类及不含 smoke/test 类已核对；`install-local.ps1 -VerifyOnly` 新旧安装目录迁移模拟通过。
- 日常 GoLand 已安排下次启动更新为 0.3.3，旧版及原队列备份于 `build/install-v033/backup-20261109-115456`。独立测试实例已关闭，日常 IDE 未重启；新版实际加载待用户重启后确认。

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
- 本机 `gradlew buildPlugin` 曾因访问 Gradle 插件仓库时 TLS 握手失败；标准 Gradle 构建现已在远端 Windows／Java 25 通过，本机仍可使用 SDK 构建脚本。
- GitHub Actions 已支持 main 推送、PR 和手动触发；main 的正式版本自动发布已启用。云端回归不包含完整 IDE 集成与物理鼠标体验验证。

## 本地操作隔离

测试仓库、账户无关 IDE 配置和插件都在 `build/` 内；未读取或修改业务仓库的 Git index，也未将测试工具装进日常 IDE。
