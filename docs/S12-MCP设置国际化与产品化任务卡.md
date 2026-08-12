# S12 MCP、设置、国际化与产品化任务卡

> 阶段：S12 发布候选产品化
> 状态：代码与发布链加固已通过当前候选本地统一门；新 HEAD 远端、真实签名、Marketplace 初始化、环境审批和副屏实机未验收

## 目标

把 S0～S11 的本地 IDE 能力收束为可配置、可审计、可升级和可发布的产品能力；新增默认关闭的本地 MCP 服务，但不得扩大既有写入权限或绕过预览、冲突检查与撤销机制。

## 固定安全边界

- MCP 默认关闭；关闭时不创建监听器、线程池、会话或后台轮询。
- 只绑定 `127.0.0.1`，使用每次启动随机生成的高熵访问令牌；令牌不得进入设置 XML、项目文件、日志、异常或导出文件。
- 请求必须同时通过回环来源、Host、Origin、Bearer token、会话、请求体上限和工具白名单校验。
- 只读工具只能读取当前已打开项目的静态模型或已经加载的数据库元数据，不连接未授权数据源，不触发网络下载。
- 写工具默认禁用；启用后也必须先形成不可变预览，再由独立确认调用复核 TOCTOU，并复用既有 IDE Command、Undo 和失败恢复。
- 设置导入导出只包含非敏感字段；密码、MCP token、数据库凭据和运行期会话不得出现。
- 损坏、未知未来版本或超限配置必须整体拒绝，保留当前有效设置；持久化旧版本按显式迁移规则归一化。
- 中文与英文资源键必须一一对应；用户可见界面、错误、通知和帮助不得新增硬编码文本。

## 分批实施

| 批次 | 交付内容 | 验收证据 |
|---|---|---|
| S12-A | 设置 schema v2、迁移、确定性导入导出、恢复默认 | v1→v2、损坏/未来版本/重复键/敏感键拒绝、往返与默认值测试 |
| S12-B | 默认关闭的回环 MCP 传输与会话 | 未启用零监听；回环/Host/Origin/token/body/method/session 正反例；关闭后线程和端口释放 |
| S12-C | Mapper、statement、参数、表列、数据源、引用只读工具 | 精确项目选择、Dumb/取消/失效/多目标/加载中与上限测试 |
| S12-D | CRUD、statement、测试骨架受控写工具 | preview→confirm 两阶段、白名单、TOCTOU、只读/冲突/取消、单命令 Undo 与失败恢复 |
| S12-E | 中英文资源、图标、隐私、许可证与第三方清单 | 资源键对称、无新增界面硬编码、插件描述与文档校验、隐私负面承诺测试 |
| S12-F | CycloneDX SBOM、签名、GitHub 草稿 Release、Marketplace 与升级降级回滚 | 可复现 SBOM、无密钥仓库、候选 SHA/必需检查/制品身份/签名/来源证明守门、首次 Marketplace 人工初始化、Alpha/Beta/RC 升降级演练 |

## 设置 schema v2

- 保留 `showNotifications`、`allowNetworkAccess`、`experimentalFeatures`；
- 新增 `mcpEnabled=false`、`mcpPort=0`、`mcpWriteToolsEnabled=false`、MCP 工具白名单和界面语言覆盖；
- `mcpPort=0` 表示由系统分配临时端口，固定端口只接受 1024～65535；
- 旧 schema v1 保留原值并为新增字段填入保守默认值；
- 导出格式稳定排序、UTF-8、无时间戳，便于审计和差异比较。

## 统一质量门

- S12 核心代码行覆盖率不低于 85%，整体覆盖率不低于 70%；
- Checkstyle、完整平台测试、两个 Maven 语料、插件结构、最低 252 Verifier 与支持矩阵全绿；
- 安全扫描证明设置、导出、日志、测试报告和 ZIP 不含明文密码、token 或私钥；
- 发布环境固定为 `release-signing`、`release-github`、`marketplace-preview` 和 `marketplace-production`；签名 secret 与 Marketplace token 必须放入对应 Environment，不能放进仓库或让用户在对话中粘贴；
- `v*` 标签必须指向触发时 `origin/main` 的精确提交并通过同一 SHA 的必需检查；GitHub 草稿 Release 与 Marketplace 发布均复核制品身份、SHA-256 和来源证明；
- 没有副屏时不启动 IDEA、`runIde`、生命周期脚本或 Computer Use；真实设置页、MCP 客户端和升级/卸载路径保留为待验，不能冒充完成。

截至 2026-08-12，当前工作区已通过独占本地统一候选门：722/722 平台测试，15135/17958（84.28%）整体行覆盖率，各分区覆盖率、SBOM、本地化、结构和最低 `IU-252.28539.54` Verifier 均通过；临时密钥签名、验签、篡改拒绝、归档身份以及 shell/生命周期/发布静态契约均通过。这些是本地非生产证据，不替代新 HEAD 远端、Environment 审批、生产密钥签名、Marketplace 接收或副屏验收。
