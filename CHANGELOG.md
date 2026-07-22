# Changelog

## v1.0.0 (2026-07-22)

LeoAI 首个正式开源版本。本版本以全新安装为发布基线，统一 Java、PHP、Web 控制台和部署制品的版本与运行约定。

### 运行时与组件

- Java 与 PHP Puppet 运行时采用统一的能力注册、调用和响应协议，平台按运行时能力展示可用功能
- Java Component 完成生命周期、资源回收、线程池复用、任务数量上限和空闲清理优化
- PHP Component 补齐系统、文件、网络、数据库和基础管理能力，并控制 Puppet 侧代码体积
- Java Component 支持会话级类名策略，构建阶段可配置运行时类名画像
- 组件字节码构建移除调试信息、冗余元数据和固定诊断文本，构建产物同步加入兼容性验证

### 控制台与平台服务

- 重构 Puppet 管理、策略配置、文件系统指标和资源概览界面
- 文件系统容量统一按挂载点聚合，修复容量显示为零和瓶颈挂载点识别问题
- AI SSE、后台任务和下载/上传任务采用有界执行器及统一清理路径
- Java/PHP 节点路由与插件服务解耦，减少控制器对具体运行时实现的依赖
- 前端生产制品随正式 JAR 一并发布

### 账户与部署

- 内置账号为 `admin`，初始密码为 `54ikun`
- 首次登录必须修改初始密码；完成修改前，仅开放登录状态、修改密码和退出接口
- 所有 Maven 模块、Web 控制台、系统配置、Docker 镜像和发布制品统一使用 `1.0.0`
- 标准制品名称为 `LeoAi-1.0.0.jar`，运行环境要求 JDK 17
- Docker 默认从 `v1.0.0` Release 下载正式 JAR，数据目录持久化到 `/app/data`

### 发布说明

- v1.0.0 采用全新数据库初始化流程，以仓库内 `schema.sql` 和 `data.sql` 为唯一基线
- 推荐使用全新数据目录部署正式版本
- 完整使用说明见 `README.md`，英文说明见 `README_EN.md`

### 验证

- JDK 17 执行完整 Maven 测试与打包：419 个测试通过，2 个环境相关测试跳过
- Web 控制台：186 个测试通过，生产构建通过，依赖审计为 0 项
- 全新 SQLite 数据目录启动验证通过；首次登录强制改密、接口访问限制及改密后恢复访问均已验证

---

## v0.0.8 (2026-07-06)

### 虚拟终端稳定性与资源回收

- **终端进程懒启动**：`ExecCommandComponent` 不再因为 `read` / `stop` 请求自动创建 shell，只有首次 `write` 才启动远端进程，避免空轮询制造无效进程
- **会话关闭回收**：前端关闭会话、重置终端工作台或组件卸载时会发送 `stop`，主动清理后端维护的终端进程
- **空闲与数量保护**：终端进程增加 30 分钟空闲清理，并限制单环境最多 32 个活跃进程，降低长时间使用后的资源泄漏风险
- **命令入口校验**：`/puppet-node/command` 入口显式校验会话存在性和 `TerminalCapable` 能力，并刷新 puppet 会话活跃时间

### 前端页面体验

- 优化终端工作台布局：左侧会话栏更紧凑，顶部工具条和底部状态栏收窄，终端视口获得更多可用高度
- 会话列表选中态从大面积色块改为细边框和竖线提示；新建按钮图标化，减少窄栏挤压
- 收紧 xterm 容器 chrome、边框和正文内边距，让终端窗口更贴近主工作区
- 优化文件管理模块各组件 UI，统一工具栏、文件列表、编辑/预览、传输操作等区域的视觉层级与布局密度
- 优化管理后台 UI，提升用户管理、团队管理、AI 配置、审计日志等后台页面的一致性和可读性

### 文档

- 同步刷新中英文 README：移除固定 `0.0.7` 示例版本号，改用 `LeoAi-<version>.jar` 占位；Docker `JAR_URL` 示例改为通用版本占位
- README 中的 AI Skills 和虚拟终端描述同步到当前实现，不再硬编码旧的 Skill 数量和 Web Terminal 口径
- 前端 README 扩展为完整工程说明，补充技术栈、模块结构、本地开发、构建部署、开发约定和虚拟终端模块说明

### 验证

- 前端 `npm run lint` 通过（仍存在项目既有 warning），`npm run build` 通过
- 后端使用 JDK 17 执行 `./mvnw -q -DskipTests compile` 通过

---

## v0.0.7 (2026-06-24)

### AI Agent 架构精简

本次重构将 Agent 工具数从 ~116 削减到 ~42（64%），移除子 Agent 调度层，所有工具直接注入主 Agent，消除 dispatch 间接开销。

- **纯 OS 命令包装工具移除**：ProcessTools、NetworkInfoTools、UserAccountTools、MountDiskTools、DockerContainerTools、SuidCapabilityTools、InstalledSoftwareTools、ScheduledTaskTools、ServiceManagerTools、EventLogTools、WifiTools、SuidTools、RegistryTools、DiskTools 共 14 个 @Component 删除。这些工具底层仅封装 `exec` 命令，AI 现在直接调 exec 自行解析输出
- **FileTools 精简**：15 方法 → 4 方法，保留 `startDownloadTask` / `startUploadTask`（分块传输）+ `readTextFile`（会话缓存）+ `searchFileContent`（grep 封装，结构化返回 `[{file, lineNumber, content}]`）
- **CatalinaTools 合并**：`unloadFilter` / `unloadServlet` / `unloadValve` / `unloadListener` / `unloadController` / `unloadInterceptor` 合并为 `unloadWebComponent(componentType, contextName, identifier)`，保留 `getCatalinaInfo`
- **子 Agent 架构完全移除**：删除 `ReconSubAgent`、`ExploitSubAgent`、`PersistenceSubAgent`、`SubAgentDispatchTools`（425 行）、`SubAgentPrompts`。所有工具（ScanTools、BrowserDataTools、CredentialHarvestTools、ClipboardTools、HttpRequestTools、ScriptTools、SqlTools、ResourceTools、JavaPluginTools）直接注入主 Agent，消除「主 Agent 推理 → 派发 → 子 Agent 推理 → 合并」的 3 轮 token 消耗
- 移除子 Agent 相关线程池（`subAgentToolExecutor`、`subAgentDispatchExecutor`）和 `subAgentMemoryProvider`
- `AiAgentProperties` 清理 `subMaxParallelTools`、`subMaxContextTokens`、`SubAgentConfig`、`autoGrantSession`、`confirmationTimeoutMinutes` 等冗余配置

### 上下文压缩与 1M 窗口支持

- **动态上下文窗口**：`chatMemoryProvider` 不再硬编码 180K，改为从 `AiModelConfig.contextWindowTokens` 读取；未配时根据模型名推断（gpt-4o→200K、gemini→1M、claude→200K 等）
- **大窗口用消息条数淘汰**：窗口 >96K 时改用 `MessageWindowChatMemory`（按消息数），避免 `CharBasedTokenEstimator` 在百万 token 量级下累积误差
- **上下文压缩**：新增 `ContextCompressionService` + `CompressingChatMemory`。窗口 ≥100K 且当前 token 数超过 80% 阈值时，自动取最早 20 条消息调用 LLM 压缩为技术摘要，保留关键信息（OS/中间件/凭据/文件路径/操作结果）

### 任务计划重构

- **PlanBar 独立顶栏**：新增 `PlanBar.vue` 组件，plan 不再混在 Task Tree 节点中渲染，而是作为 sticky 独立顶栏吸附在对话区顶部。展开态展示彩色进度条 + 步骤列表，收起态显示当前执行步骤。带展开/收起按钮和 320px 最大高度滚动
- **Tool 节点标注计划步骤**：后端 SSE 事件携带 `planStepIndex`，前端 tool 节点左侧显示步骤编号圆点（如 ②），hover 显示对应步骤描述
- **工具结果自动回写**：`beforeToolExecution` 自动检测活跃 plan 的 running 步骤，注入 `AiToolContext.planStepIndex`；`afterToolExecution` 自动将工具结果摘要写入 `step.result`，AI 无需手动调 `updatePlanStep` 记录
- **跨轮 plan 持久化**：新轮次启动时，如果存在 `IN_PROGRESS` 状态的 plan，自动注入 SSE 事件让前端 PlanBar 跨轮可见

### 用户确认体系移除

用户通过认证登录 + 建立 puppet 会话 + 对 AI 下指令的信任链已完备，中间插入确认弹窗无安全增量，只会打断 AI 连续执行。本次彻底移除确认体系：

- 删除 6 个文件：`AiToolConfirmationCallback`、`ToolExecutionDeniedException`、`AiConfirmationRequest`（后端）、`AiSessionGrantBadge.vue`、`AiToolConfirmDialog.vue`（前端）
- `AiThread` 移除 `pendingConfirmations`、`sessionGrantedTypes`、`sessionGrantedAll` 及全部确认/授权方法
- `PuppetNodeSession`、`PlatformAiState`、`AiStateAccessor`、`PuppetNodeAiStateAccessor` 同步清理确认/授权相关接口和实现
- `PuppetNodeAiController`、`PlatformAiController` 移除 `/confirm`、`/grant` 端点
- 前端 `useAiChat.js` 移除 `confirmationQueue`、`confirmationResponding`、`respondToConfirmation`、`confirmApi`/`grantApi` 参数
- `AiAgentProperties` 移除 `autoGrantSession`、`confirmationTimeoutMinutes` 等确认相关配置

### Bug 修复

- **AiToolRegistry 命令执行映射过期**：command 类别映射了 7 个已不存在的旧工具名（`creat`/`write`/`stop` 等），导致 `exec` 调用时 `getCategoryKey("exec")` 返回 null。已修正为 `m.put("exec", "command")`，同步更新 container/file\_write 类别映射

### 升级提示

- 前端需重新构建（`npm run build`），`PlanBar.vue`、`AiSessionGrantBadge.vue`、`AiToolConfirmDialog.vue` 等组件有增删
- 后端 `AiAgentProperties` 配置项 `sub-*`、`auto-grant-session`、`confirmation-timeout-minutes` 已移除，若 `application.yml` 中有自定义值需删除
- 14 个已删除的工具类对应 `.payload` 不受影响（这些是纯 Java 服务端代码，不涉及 puppet 端组件）
- 上下文压缩依赖 `delegatingChatModel`（非流式 LLM），确保模型配置正常可用

---

## v0.0.6 (2026-06-20)

### Puppet 组件可用性增强（红队 / 排障场景重点）

针对「独立 Tomcat 部署 + puppet 注入 commonLoader + webapp idle」这类典型场景，把过去依赖活跃请求 / contextClassLoader / 内部静态字段才能工作的几个核心组件全面重构，覆盖 Tomcat 6/7/8/9/10/11、WebLogic 全版本及 Spring 5/6（含 Tomcat 10+ 的 jakarta.servlet）。

- **TomcatCatalinaManageComponent**
  - `unLoadFilter`：改用公开 API `removeFilterMap(FilterMap)` + `removeFilterDef(FilterDef)`，同时清空 `filterConfigs` 缓存并对其调 `release()`，让卸载**即时生效**而不是等下一次请求；公开 API 不可用时自动退到字段直写
  - `getAllListener`：原本只取 `getApplicationEventListeners`（事件监听器），导致大多数 Context 看着「没有 Listener」；新增 `getApplicationLifecycleListeners` 同时收集生命周期监听器（ServletContextListener / HttpSessionListener / Spring `ContextLoaderListener` 都在此），返回结果带 `category=event|lifecycle` 字段；bootstrap CL 加载的 listener `getClassLoader()` 返回 null 时降级显示为 `<bootstrap>`，避免 NPE
  - `unLoadListener`：候选字段表扩展为 6 个，覆盖 event 和 lifecycle 两类，每类各 3 个 Tomcat 版本字段名（`applicationEventListenersList/Objects/applicationEventListeners` + `applicationLifecycleListenersList/Objects/applicationLifecycleListeners`）；遍历策略改为「全部字段都尝试 remove」而非命中即 break，杜绝同时实现 Lifecycle + Event 接口的 listener 卸载残留
- **WeblogicCatalinaManageComponent**
  - 移除类加载期 `static contexts = getContext()` 缓存（首次扫到空就永远空）
  - `getContextsByMbean()` 加载 `WebAppServletContext` 时按 `classLoader → systemClassLoader` 两段降级
  - `getContext()` 新增第 3 条兜底路径 `getContextsByPlatformMbean()`：通过 `com.bea:Type=ApplicationRuntime,*` 反推 `WebAppServletContext`，覆盖 idle / 普通 CL 注入场景
  - `getCatalinaInfo` / `getAllListener`：补齐 Listener 采集，覆盖 `_servletContextListeners` / `_sessionListeners` / `_requestListeners` / `_asyncListeners` 等 8 类字段（带/不带下划线两套命名兜底），结果带 `category` 字段，按 identity 去重
- **SpringFrameworkManageComponent**
  - 移除 `static Object context` 缓存，改为 instance 字段每次现取
  - 新增**路径 3**：从 Tomcat StandardContext 反推 ServletContext → `WebApplicationContextUtils.getWebApplicationContext()`，解决 idle Spring Boot 部署 + 全局 CL 注入下 `RequestContextHolder` / `LiveBeansView` 全部失效的问题
- **CredentialHarvestComponent**
  - `getSpringContext()` 同步加 Tomcat MBean 兜底路径，并兼容 `javax.servlet` / `jakarta.servlet` 两套签名（Spring 5+Tomcat 9 与 Spring 6+Tomcat 10+）
- **DatabaseComponent**
  - JDBC driver 加载从单一 contextCL 改为 `contextCL → systemCL → 所有 Tomcat WebappClassLoader` 三层降级
  - 找到 driver 后直接 `Driver.connect(url, props)`，绕开 `DriverManager` 的 SecurityManager 同 CL 校验，使 webapp `WEB-INF/lib` 内的 driver 也能跨 CL 使用
- **ResourceComponent**（重写源码，原仅留二进制 .payload）
  - `run()` 改用 `catch (Throwable)` 兜底 + 无条件回写 results，根除 puppet 偶发吞响应导致前端报「响应解码结果为空」
  - 资源加载同样三层 CL 降级，可直接读 webapp 内的 `.class` / `application.yml` / `META-INF/MANIFEST.MF` 等
  - 响应同时塞 `bytecode` 与 `data` 两个字段，兼容历史调用方

### 新功能

- **类与资源浏览器**：PuppetConsole 新增独立 Tab「类与资源」，支持
  - 双输入模式：类名（自动转 `com/example/Foo.class`）/ 任意 classpath 路径
  - 自动识别响应内容：`0xCAFEBABE` 走反编译展 Java 源、纯文本走 UTF-8 文本展示（256K 截断）、其他走十六进制 dump（前 4KB + ASCII 列）
  - 配套操作：复制（按预览类型自动选）、下载、清空、最近 6 条历史
  - 后端新接口 `POST /puppet-node/resource/get`，含审计日志、magic-byte 类型识别、反编译失败自动降级到十六进制

### 体验优化

- **「插件调用」模块改名为「脚本与插件」**：原命名只对应模块内一个按钮的语义（仅覆盖 ~25%），不能涵盖脚本编辑、Java Class 临时执行、保存为插件、插件库浏览四类核心动作；同步更新 README 与英文文档对应段落（Plugin Invocation → Scripts & Plugins）
- **类字节码弹窗比例修复**：在 1440px+ 宽屏下原 `width="80vw"` 会出现「左右大空白、上下挤压」并伴随 Monaco minimap 拖出长条
  - Dialog 宽度按视口分档（≥2400=1800px / ≥1920=1500px / ≥1440=1200px / ≥1024=920px / 移动端 92vw），高分屏不再「漂在中间」
  - Dialog 显式 `height: 90vh`、`top: 4vh`、内部 flex 布局，编辑器随高度自然 fill，不再出现「弹窗矮、内容溢出」
  - 抽出 `useResponsiveDialogWidth` composable，断点表可覆盖；JavaPlugin 调用弹窗（原硬编码 `width="1000px"`）改用同一 composable，自带稍窄一档（≥2400=1600px / ≥1920=1300px / ≥1440=1100px / ≥1024=1000px）匹配其左右两栏布局
- **资源浏览器代码预览升级为 Monaco**：原 `<el-input type="textarea" :rows="22">` 在大文件下没有语法高亮、Ctrl+F、折叠
  - 新增 `<CodePreview>` 通用组件，封装 monaco 编辑器实例的复用 / 主题切换 / 内容增量更新（避免每次重建丢滚动）
  - ResourceBrowser 的 Java 反编译预览、文本预览全部改走 CodePreview
  - 文本预览自动按扩展名推断语言（json/xml/yaml/properties/sql/sh/js/ts/html/css/md/py/java），常见配置文件直接吃语法高亮
- **NetworkConnectionService 数据返回 0 条修复**：原 macOS 链路在某些场景上 `lsof -i -n -P` 命令执行了、回显却被 `2>/dev/null` 吞没，`output.trim().length() > 10` 判断仍通过（shell prompt 自身就 >10），结果走进 `parseLsof` 但解析出 0 条；fallback 到 netstat 的逻辑也不会触发
  - 命令前缀加 `PATH=$PATH:/usr/sbin:/sbin:/usr/local/sbin`，覆盖 puppet 非 login shell 默认 PATH 缺 sbin 的情况
  - 重定向改为 `2>&1`，错误信息不再丢，便于诊断
  - 抽 `looksLikeRealOutput(output, expectedHeader)`：见 header 关键字才算"真有输出"，否则识别 `command not found / Permission denied` 等错误模式后直接 fallback
  - 解析后若仍是 0 条，diagnostics 里附带 `preview=…` 输出片段，下次再有问题能直接看到 shell 回显的原始内容
  - 现在 macOS / Linux 两侧都遵循「真解析出连接才记 source、否则继续 fallback」的策略
- **NetworkConnectionService 响应结构平铺修复**：上一轮修好后端能拿到 315 条连接，但前端列表仍空。根因是 `ControllerUtil.handlePuppetCall` 看到 service 返回的 `code=200` 会再调 `ApiResponse.success(result)` 包一层，service 又自己嵌了 `data:{...payload}`，最终 HTTP body 变成 `res.data.data.connections`，而 `NetworkConnectionManager.vue` 读的是 `res.data.connections`
  - `list()` / `summary()` 把 `result.put("data", data)` 改为 `result.putAll(data)`，让 payload 字段（connections / total / byState 等）直接平铺到 service 返回 map 上
  - 与 `BrowserDataService` 等其他 puppet service 的返回风格对齐：service 只返回 `{code, ...payload}`，不再自己嵌一层 data
- **parseLsof / parseSs 跳过 shell 噪声行**：原 parser 假定第 0 行是 header、第 1 行就是数据；但通过 puppet shell 会话执行时，前面会带 prompt + 命令回显
  - 改为先扫描定位 header 行（lsof 的 `COMMAND ... PID ... NAME`、ss 的 `Netid|State ... Local`），从 header 之后开始解析
  - 跳过 `$` / `#` / `%` 起始的 prompt 行
  - parseLsof 增加 NODE 列校验（必须是 TCP/UDP/IPv4/IPv6 才算合法行），并对 `IPv4/IPv6` 协议从 NAME 中再抽取实际的 TCP/UDP 标签

### 安全加固（继承自 Unreleased）

- **YAML 反序列化**：`SkillRegistryService` 和 `SkillController` 改用 `SafeConstructor`，禁止 frontmatter 中通过 `!!java.*` 标签实例化任意类
- **Disguise 接口鉴权**：`del-disguise` / `update-disguises` 显式校验登录态；`test-disguises` / `preview` 因会动态编译并执行 Java 代码，在入口处增加未登录拦截
- **Zip Bomb 防护**：新增 `org.leo.core.util.SafeZipReader`，对 Disguise / Plugin / Fingerprint 三处 zip 导入路径强制限制条目数（1000）、单条目大小（5 MB）、解压后总大小（50 MB），超限抛 `ZipLimitExceededException`

### 重构（继承自 Unreleased）

- 后端 Plugin 模块导入冲突策略从 `boolean overwrite` 改为内部 `ConflictPolicy` 枚举（SKIP / OVERWRITE），与 Disguise / Fingerprint 风格对齐
- 前端新增 4 个 composable：`useDialogVisible`（dialog v-model 收口）、`useDirtyTracker`（表单脏检查）、`useSaveShortcut`（Ctrl/Cmd+S 保存）、`useConfirmClose`（关闭前确认）
- 前端 `AddPluginDialog` / `EditPluginDialog` 提取共享子组件 `PluginFormFields`，删除约 260 行重复模板
- 前端新增 `utils/downloadBlob.js`，统一替换 4 处分散实现的 blob 下载逻辑
- 前端 11 个 dialog 组件迁移到 `useDialogVisible`，移除老式的双 `watch` 同步模式

### 兼容性

| 场景 | 0.0.5 | 0.0.6 |
|---|---|---|
| Idle Tomcat 容器面板列表（无活跃请求） | ✗ | ✓ |
| Idle Spring Boot 凭据采集 / 框架信息 | ✗ | ✓ |
| WebLogic 普通 CL 注入下的容器列表 | ✗ | ✓ |
| Tomcat 6 卸载 Listener | ✗ | ✓ |
| Filter 卸载即时生效 | ✗ | ✓ |
| 跨 CL 使用 webapp 内 JDBC driver | ✗ | ✓ |
| 查看 webapp 内的 class 字节码 | ✗ | ✓ |

### 升级提示

- 容器与 Spring 相关 `.payload` 二进制已重新生成，puppet 端会按需自动重载，**无需手动重启目标进程**
- 旧版本 `LeoAi-0.0.5-SNAPSHOT.jar` 与新版 `.payload` 不兼容（component 接口和反射字段名都有调整），请整体升级到 0.0.6
