---
name: shell-obfuscation
description: 理解 LeoAI Java/PHP WebShell 与 Java 内存马生成链路，并根据 Puppet runtime、通信配置、Java/Servlet 或 PHP 环境及生成器能力灵活选择合法参数。用户要求生成、变体生成、调整兼容性或排查 Shell 无法连接时使用；始终通过 ShellGeneratorTools 完成确定性生成与结果交付。
---

# Shell 生成与结构变体

先建立运行模型和适配方案，再选择工具参数。工具是实现手段，不是工作原理；不要把某个内置 JSP 骨架当成唯一答案，也不要虚构工具、参数或生成结果，生成的文件在保证代码正常运行的情况下，尽可能像一个正常业务的文件。


## 运行模型

把一次 Shell 生成理解为以下四层的组合：

1. **运行时与通信配置**：`runtime` 决定走 Java 还是 PHP 生成器；`protocol`、`reqDisguiseId`、`respDisguiseId` 决定请求与响应如何封装。它们必须来自同一个目标 Puppet 的当前配置。
2. **运行时核心**：
   - Java 使用 LeoCore 接收请求、按配置解包和解密、处理协议，再把结果加密并写回响应；核心类字节码由 `ShellGenerator` 构建。加载和承载 LeoCore 的代码只是适配层，可以有不同结构。
   - PHP 使用 PHP Core 处理 Envelope 协议的 `test`、`forward`、`load` 和 `invoke` 操作；功能组件按需投递并使用运行时缓存。
3. **承载方式**：
   - Java WebShell 是 JSP/JSPX 入口，负责加载并调用核心通信类。
   - Java 内存马由核心类、容器注入器和 Packer 组合；注入器负责注册到目标容器，Packer 决定最终交付格式。
   - PHP 当前只生成 `.php` WebShell，不支持 Java 内存马、JSP 模板或 Servlet 参数。
4. **结果交付**：生成器把完整结果写入 `ShellResultStore`，工具只返回 `resultId`、元数据和取回按钮。不要在回复中重建或转述完整代码。

只要通信配置匹配、目标兼容性成立、生成链路完整，加载方式、类名、请求读取方式和代码结构都可以变化；这些变化不得破坏 LeoCore 调用契约和协议语义。

## Java LeoCore 调用契约

围绕数据流理解 Java Shell，不要围绕固定模板理解：

1. 获得与当前通信配置匹配的 LeoCore 类，并确保它在目标 ClassLoader 可用。
2. 读取本次请求的原始请求体字节。
3. 创建 `ByteArrayOutputStream`，写入完整请求字节。
4. 创建 LeoCore 实例并调用其 `equals(buffer)`。当前 LeoCore 只在参数是 `ByteArrayOutputStream` 时进入通信处理。
5. LeoCore 从 buffer 读取请求，完成解码、调度和响应编码；返回时会先重置同一个 buffer，再写入响应字节。
6. 将该 buffer 当前内容原样写入 HTTP 响应体。

因此，`Class.forName(...).newInstance().equals(buffer)` 只是现有模板实现上述契约的一种写法，不是原理本身。不要把以下内容误判为必须固定：

- 类是否通过 `Class.forName` 查找。
- 类字节如何携带、还原和定义。
- 请求体通过哪种无损方式读入 buffer。
- 适配逻辑写在一个 scriptlet、声明方法还是若干辅助方法中。
- 变量名、类名、异常边界和初始化检查的组织方式。

无论结构如何变化，都要验证：请求字节没有丢失或重复、只调用一次 LeoCore、响应取自调用后的同一 buffer、异常不会混入协议响应体。

## 不变量与可变量

### 必须保持的不变量

- `runtime`、`protocol`、请求伪装器和响应伪装器与目标 Puppet 当前配置一致。
- Java 目标的 Java 版本、Servlet 命名空间、服务器类型与注入器/Packer 兼容。
- PHP 目标使用支持 PHP 源码且 `protocolVersion=2` 的请求/响应伪装器；当前协议只能为 `http`。
- Java 适配层最终仍完成 LeoCore 可用性准备、请求字节传入、单次调用和响应字节回写；具体实现路径不固定。
- 对于 Java 内存马自定义 JSP 模板，`{{base64Str}}` 恰好出现一次且语义不变。
- 对于该类模板，同名 `{{VAR:name}}` 和 `{{CLS:Name}}` 占位符保持一致；不要提前替换为固定名称。
- 完整生成结果通过 `resultId` 交付。

### 可以按需求变化的参数

- Java WebShell：`shellType`、`coreClassName`、`targetJavaVersion`、`servletNamespace`、`respCode`、`jspObfuscationSteps`。
- Java 内存马：`serverType`、`shellType`、`packerType`、`urlPattern`、Header、类名、Java 版本、Servlet 命名空间、模块兼容选项、混淆步骤和 JSP 模板结构。
- PHP WebShell：`outputMode`、可选 Header 守卫、`respCode` 和用于生成结构变体的 `seed`。
- 用户未指定类名时保留为空，让生成器随机生成；不要为了“更灵活”而强行填写所有可选项。
- Java WebShell 的 `jspObfuscationSteps=null` 表示未配置并输出原始生成骨架，空列表表示明确关闭全部步骤；Java 内存马的默认策略以生成器元数据为准。

## 参数选择原则

1. 先读取真实配置和元数据，再决策；不要根据记忆猜测枚举值。
2. 先根据 `getPuppetShellConfig()` 返回的 `runtime` 分流。不要把 PHP Puppet 交给 Java 生成器，反之亦然。
3. Java 用户明确给出目标版本或命名空间时按其选择；未给出时使用 `auto`。
4. Java 仅从 `serverInjectorTypes`、`packerTypes` 和可用性信息中选择组合。
5. Java 检查 `packerCompatibility`、`packerAvailability` 和 `packerObfuscationSteps`；优先选择无兼容性警告的组合。
6. Java 仅使用元数据列出的混淆步骤 ID，并保持用户要求的顺序。不要把自然语言策略名称当作步骤 ID。
7. `byPassJavaModule` 只在元数据显示相应 Packer 支持且目标确有模块兼容需求时设置。
8. `servletNamespace=auto` 当前可能解析为 `javax`；已知目标使用 Jakarta 时必须显式传 `jakarta`。
9. PHP 只使用 `runtimeGenerators.php` 声明的 artifact、协议、输出模式和运行要求。

## Java WebShell 工作流

先基于 LeoCore 调用契约设计适配方案，再把方案映射到现有生成器能力。`generateWebShell` 是默认物化工具，不是 Java Shell 原理的定义。当前工具不接收自定义 JSP 模板，因此不要把 `mutateJspTemplate` 的结果传给它。

1. 若没有 `puppetId`，先查询可用 Puppet，让用户目标或上下文决定唯一节点；存在多个合理目标时不要猜。
2. 调用 `getPuppetShellConfig(puppetId)`。
3. 明确本次适配目标：JSP/JSPX、Java 版本、Servlet 命名空间、协议、响应码，以及是否需要可复现的结构版本。
4. 调用 `getShellGeneratorMeta()`，把结构目标映射为生成器实际支持的参数和步骤；不要为了调用工具而反过来扭曲需求。
5. 调用 `generateWebShell(...)`：
   - 原样传入第 2 步返回的通信配置。
   - `shellType` 只能使用 `JSP` 或 `JSPX`。
   - 未指定的可选项使用工具默认值。
6. 检查返回的 `meta`，确认协议、版本、命名空间和类型符合请求；结构变化不能破坏 LeoCore 六步调用契约。
7. 如果现有工具参数无法表达用户要求的适配结构，明确报告工具边界，不要假装已经生成该结构，也不要退回固定模板冒充满足需求。
8. 回复中原样嵌入工具返回的 `[[shell-result:...]]` 按钮。

## PHP WebShell 工作流

PHP 走 runtime generator，不调用 `generateWebShell`、`generateMemoryShell` 或 `mutateJspTemplate`。

1. 调用 `getPuppetShellConfig(puppetId)`，确认 `runtime=php` 且 `protocol=http`。
2. 调用 `getShellGeneratorMeta()`，读取 `runtimeGenerators.php`。当前只使用其中真实声明的能力：
   - `artifactType=webshell`
   - 最低 PHP 版本及伪装器附加要求
   - `outputModes` 和每种模式的 `requirements`
3. 选择 `outputMode`：
   - `compact`：默认选择；源码精简，不额外要求 zlib。
   - `packed`：只有目标具备元数据列出的 zlib 和相关函数时选择。
   - `portable`：用于兼容性优先或排障，源码展开且便于检查。
4. Header 守卫可选，但 `headerName` 与 `headerValue` 必须同时设置或同时留空。不要在回复中显示 `headerValue`。
5. `respCode` 默认 200；不要使用 204、205、304，因为它们不允许所需响应体。
6. `seed` 留空时让生成器随机生成。需要可复现结果时才传固定 seed；同一 seed 与同一配置用于稳定复现，不要声称它改变协议。
7. 调用 `generatePhpWebShell(...)`，原样传入 Puppet 的协议和伪装器 ID。
8. 检查返回元数据中的 `minimumVersion`、`requirements`、`outputMode`、`headerGuardEnabled` 和 `warnings`，再交付取回按钮。

## 内存马工作流

1. 调用 `getPuppetShellConfig(puppetId)` 获取真实通信配置。
2. 调用 `getShellGeneratorMeta()` 获取服务器、注入器、Packer、版本和混淆能力。
3. 根据目标环境选择兼容组合；不要仅凭名称推断兼容性。
4. 只有当用户明确要求“结构变体”且 Packer 是 JSP 模板路径时，才调用：
   `mutateJspTemplate(packerType, byPassJavaModule, mutationHint)`。
5. 调用 `generateMemoryShell(...)`。如第 4 步成功，把 `mutatedTemplate` 传入 `customJspTemplate`；否则留空。
6. 检查返回的 `compatibilityWarnings`：
   - 无警告：正常交付。
   - 有警告但仍成功：明确告诉用户风险，不声称已验证可运行。
   - 生成失败：根据错误重新选择元数据中存在的兼容组合，最多重试一次。
7. 回复中原样嵌入工具返回的 `[[shell-result:...]]` 按钮。

## 模板结构变体

模板变体只改变代码结构，不改变功能边界。向 `mutationHint` 描述结构和兼容性目标，例如：

- `拆分为少量辅助方法，保持 Java 8 语法和原占位符`
- `调整 scriptlet 与声明块布局，避免新增 import`
- `减少反射层级，优先保证 Jakarta 环境可编译`
- `保持线性控制流，缩短模板并保留所有占位符`

不要要求模板变异器添加新的加载机制、环境探针、认证后门、外部通信或持久化逻辑。

收到 `mutatedTemplate` 后，只把它交给 `generateMemoryShell`。不要手工替换占位符，也不要把模板直接当作最终生成结果。

## 灵活决策示例

- 用户只说“给这个 Puppet 生成 JSP”：读取 Puppet 配置，使用 `JSP` 和默认兼容参数生成；无需调用模板变异器。
- 用户说“给 PHP Puppet 生成 Shell”：确认 runtime 和 HTTP 协议，默认使用 `compact`；不询问 Java、Servlet 或 Packer 参数。
- PHP 环境没有 zlib 或禁用了 `gzinflate`：不使用 `packed`，改用 `compact` 或 `portable`。
- 用户需要两个可复现的 PHP 结构版本：使用两个明确不同的 seed 分别生成，并记录各自 `variantId`；通信配置保持不变。
- 用户指定 Tomcat、Java 8、`javax` 内存马：读取元数据后选择支持这组能力的 server/shell/Packer 组合，再生成。
- 用户要求同一配置的两个结构版本：第一次使用默认模板；第二次仅在兼容的 JSP Packer 上调用一次模板变异，然后分别交付两个 `resultId`，清楚标注元数据差异。
- 用户反馈无法连接：先比对 Puppet 当前通信配置与结果元数据，再检查版本、命名空间和兼容性警告；不要直接重复生成随机变体。

## 失败处理

- `Puppet 不存在`：停止生成并报告无效 `puppetId`。
- 伪装器不存在或为空：停止生成，提示先修复 Puppet 配置。
- PHP 伪装器不支持 PHP 源码或协议版本不是 2：停止生成并更换兼容伪装器。
- PHP Puppet 使用 `httpchunk`：停止生成；当前 PHP generator 只支持 `http`。
- PHP `packed` 模式缺少 zlib、`base64_decode` 或 `gzinflate`：回退到 `compact`/`portable`，不要伪造环境能力。
- 参数不在元数据中：重新读取元数据并选择合法值，不要反复提交同一无效参数。
- 模板连续变异失败：回退到内置模板；不要自行拼接 JSP。
- 工具返回成功但包含兼容性警告：把“生成成功”和“目标可运行”分开陈述。
- 没有真实 `resultId`：不得输出取回按钮或声称生成完成。

## 回复要求

简洁报告：

- 生成类型与目标 Puppet。
- 关键选择：runtime、协议；Java 报告目标版本、命名空间、服务器/注入器/Packer，PHP 报告最低版本、输出模式和运行要求。
- 兼容性警告或回退情况。
- 工具返回的取回按钮。

不要粘贴完整生成代码，不要泄露 Header 密钥或其他敏感配置；除非用户明确需要排障，只显示必要的非敏感元数据。
