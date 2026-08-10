package org.leo.jmg.mem.packer;

import org.leo.jmg.TargetJavaVersion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Packer 注册中心，基于类路径扫描自动发现所有带 {@link PackerMeta} 注解的 {@link Packer} 实现。
 * <p>
 * 支持：
 * <ul>
 *   <li>按名称（忽略大小写）获取 Packer 实例</li>
 *   <li>判断某 Packer 是否要求 AbstractTranslet</li>
 *   <li>按分组层级结构输出（供前端展示）</li>
 * </ul>
 *
 * @author LeoSpring
 */
public final class PackerRegistry {

    private PackerRegistry() {
    }

    /** name(小写) -> Packer 延迟注册项 */
    private static final Map<String, Registration> REGISTRY = new ConcurrentHashMap<>();
    /** name(小写) -> 元数据 */
    private static final Map<String, PackerMeta> META = new ConcurrentHashMap<>();
    /** 保留原始注册顺序的有序列表 */
    private static final List<PackerMeta> ORDERED = new ArrayList<>();
    /** 扫描阶段无法加载、因而无法读取 @PackerMeta 的类。 */
    private static final Map<String, String> SCAN_FAILURES = new ConcurrentHashMap<String, String>();
    private static final Comparator<PackerMeta> META_ORDER =
            Comparator.comparingInt(PackerMeta::order)
                    .thenComparing(PackerMeta::name, String.CASE_INSENSITIVE_ORDER);

    static {
        loadFromClasspathScan();
    }

    /**
     * 通过类路径扫描自动发现所有带 @PackerMeta 注解的 Packer 实现。
     */
    private static void loadFromClasspathScan() {
        PackerScanner.ScanResult scanResult = PackerScanner.scan();
        for (Class<? extends Packer> packerType : scanResult.getPackerTypes()) {
            registerClass(packerType);
        }
        SCAN_FAILURES.putAll(scanResult.getFailures());
    }

    /**
     * 手动注册一个 Packer（用于测试或编程式注册）
     */
    public static void register(Packer packer) {
        if (packer == null) {
            throw new IllegalArgumentException("packer 不能为空");
        }
        @SuppressWarnings("unchecked")
        Class<? extends Packer> packerType = (Class<? extends Packer>) packer.getClass();
        register(packerType, packer);
    }

    /**
     * 注册 Packer 类型但不执行构造函数；首次 get 时才实例化。
     */
    public static void registerClass(Class<? extends Packer> packerType) {
        if (packerType == null) {
            throw new IllegalArgumentException("packerType 不能为空");
        }
        register(packerType, null);
    }

    private static void register(Class<? extends Packer> packerType, Packer instance) {
        PackerMeta meta = packerType.getAnnotation(PackerMeta.class);
        if (meta == null) {
            return;
        }
        String key = normalize(meta.name());
        if (key.isEmpty()) {
            throw new IllegalArgumentException("@PackerMeta.name 不能为空: " + packerType.getName());
        }

        synchronized (ORDERED) {
            Registration existing = REGISTRY.get(key);
            if (existing != null) {
                if (!existing.packerType.equals(packerType)) {
                    throw new IllegalStateException("Packer 名称冲突 [" + meta.name() + "]: "
                            + existing.packerType.getName() + " 与 " + packerType.getName());
                }
                // 编程式实例注册可以覆盖尚未初始化或失败的同类型注册项。
                if (instance != null) {
                    existing.setInstance(instance);
                }
                META.put(key, meta);
                return;
            }

            REGISTRY.put(key, new Registration(packerType, instance));
            META.put(key, meta);
            ORDERED.add(meta);
        }
    }

    /**
     * 按名称获取 Packer 实例（忽略大小写）
     *
     * @param name packerType 名称
     * @return Packer 实例，未找到返回 null
     */
    public static Packer get(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        Registration registration = REGISTRY.get(normalize(name));
        return registration == null ? null : registration.getOrCreate();
    }

    /**
     * 按名称获取 Packer 实例（忽略大小写），未找到时抛出明确异常。
     * <p>
     * 供其他 Packer 在委托调用时使用，避免依赖缺失时产生 NPE。
     *
     * @param name packerType 名称
     * @return Packer 实例（非 null）
     * @throws IllegalStateException 若该名称未注册
     */
    public static Packer getOrThrow(String name) {
        Packer packer = get(name);
        if (packer == null) {
            throw new IllegalStateException(
                    "依赖 Packer [" + name + "] 未注册，请检查类路径或 @PackerMeta 注解");
        }
        return packer;
    }

    /**
     * 按名称获取元数据
     */
    public static PackerMeta getMeta(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        return META.get(normalize(name));
    }

    /**
     * 判断某 packerType 是否要求注入器继承 AbstractTranslet
     */
    public static boolean requiresAbstractTranslet(String name) {
        PackerMeta meta = getMeta(name);
        return meta != null && meta.requiresAbstractTranslet();
    }

    /**
     * 校验 Packer 与显式目标 JDK、模块绕过选项是否兼容。
     * AUTO 不按目标版本阻断。
     */
    public static PackerCompatibilityResult validateCompatibility(String name,
                                                                  TargetJavaVersion targetJavaVersion,
                                                                  boolean byPassJavaModule) {
        PackerCompatibilityResult result = evaluateCompatibility(name, targetJavaVersion, byPassJavaModule);
        result.throwIfUnsupported();
        return result;
    }

    /** 校验 Packer 是否声明支持当前传输协议。 */
    public static void validateProtocolCompatibility(String name, String protocol) {
        PackerMeta meta = getMeta(name);
        if (meta == null) {
            throw new IllegalArgumentException("不支持的 packerType: " + name);
        }
        String normalizedProtocol = protocol == null ? "" : protocol.trim().toLowerCase(Locale.ROOT);
        for (String supportedProtocol : meta.supportedProtocols()) {
            if (normalizedProtocol.equals(supportedProtocol.trim().toLowerCase(Locale.ROOT))) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Packer " + meta.name() + " 不支持传输协议 " + protocol);
    }

    /**
     * 评估兼容性。明确不兼容的条件进入 errors；依赖目标环境能力的条件进入 warnings。
     */
    public static PackerCompatibilityResult evaluateCompatibility(String name,
                                                                   TargetJavaVersion targetJavaVersion,
                                                                   boolean byPassJavaModule) {
        PackerCompatibilityResult result = new PackerCompatibilityResult();
        TargetJavaVersion target = targetJavaVersion == null
                ? TargetJavaVersion.AUTO
                : targetJavaVersion;

        PackerMeta meta = getMeta(name);
        if (meta == null) {
            result.addError("不支持的 packerType: " + name);
            return result;
        }
        Registration registration = REGISTRY.get(normalize(name));
        if (registration != null && registration.status == PackerStatus.FAILED) {
            result.addError("Packer " + meta.name() + " 初始化失败: " + registration.failureReason);
        }

        int minTargetJava = resolveMinTargetJava(name);
        if (!target.isAuto() && target.getMajor() < minTargetJava) {
            result.addError("Packer " + meta.name()
                    + " 最低要求 JDK " + minTargetJava
                    + "，当前目标为 JDK " + target.getValue());
        }
        if (!target.isAuto() && byPassJavaModule && target.getMajor() < 9) {
            result.addError("byPassJavaModule 仅适用于 JDK 9+，当前目标为 JDK "
                    + target.getValue());
        }

        Set<PackerCapability> capabilities = resolveCapabilities(name);
        for (String missingDependency : resolveMissingDependencies(name)) {
            result.addError("Packer " + meta.name() + " 依赖未注册的 Packer: " + missingDependency);
        }
        if (capabilities.contains(PackerCapability.JAVASCRIPT_ENGINE)) {
            if (target == TargetJavaVersion.JDK_17_PLUS) {
                result.addWarning("该 Packer 需要 JavaScript ScriptEngine；JDK 15+ 默认不再内置 Nashorn，目标环境需额外提供兼容 JS 引擎");
            } else if (target.isAuto()) {
                result.addWarning("该 Packer 需要目标环境提供 JavaScript ScriptEngine，auto 模式无法确认该能力");
            }
        }
        return result;
    }

    /** 返回 Packer 的目标 JDK 兼容性元数据，供 REST、AI 和前端展示。 */
    public static Map<String, Map<String, Object>> getCompatibilityMap() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (PackerMeta meta : getSortedMetadataSnapshot()) {
            Map<String, Object> compatibility = new LinkedHashMap<>();
            compatibility.put("minTargetJava", resolveMinTargetJava(meta.name()));
            compatibility.put("requiresAbstractTranslet", meta.requiresAbstractTranslet());
            compatibility.put("dependencies", Arrays.asList(meta.dependencies()));
            compatibility.put("requiredCapabilities", capabilityValues(resolveCapabilities(meta.name())));
            compatibility.put("requiredClasses", new ArrayList<String>(resolveRequiredClasses(meta.name())));
            compatibility.put("supportedProtocols", Arrays.asList(meta.supportedProtocols()));
            Registration registration = REGISTRY.get(normalize(meta.name()));
            compatibility.put("status", registration == null
                    ? PackerStatus.FAILED.getValue()
                    : registration.status.getValue());
            compatibility.put("failureReason", registration == null ? "注册项不存在" : registration.failureReason);
            result.put(meta.name(), compatibility);
        }
        return result;
    }

    /**
     * 获取所有已注册的 Packer 名称列表
     */
    public static List<String> getAllNames() {
        return getSortedMetadataSnapshot().stream()
                .map(PackerMeta::name)
                .collect(Collectors.toList());
    }

    /**
     * 按分组层级输出，供前端展示。
     * <p>
     * 返回结构：
     * <ul>
     *   <li>{@code groups}：List&lt;Map&gt;，每项含 groupName + packers 列表</li>
     *   <li>{@code ungrouped}：无分组的 packer 名称列表</li>
     * </ul>
     */
    public static Map<String, Object> getHierarchy() {
        LinkedHashMap<String, List<String>> grouped = new LinkedHashMap<>();
        List<String> ungrouped = new ArrayList<>();

        for (PackerMeta meta : getSortedMetadataSnapshot()) {
            String group = meta.group();
            if (group == null || group.isEmpty()) {
                ungrouped.add(meta.name());
            } else {
                grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(meta.name());
            }
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupName", e.getKey());
            row.put("packers", e.getValue());
            groups.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groups);
        result.put("ungrouped", ungrouped);
        return result;
    }

    /**
     * 获取指定 Packer 支持的混淆步骤 ID 列表（按注解声明顺序）。
     * <p>
     * 空列表表示该 Packer 未声明混淆步骤支持。
     */
    public static List<String> getSupportedObfuscationSteps(String name) {
        PackerMeta meta = getMeta(name);
        if (meta == null || meta.obfuscationSteps().length == 0) {
            return Collections.emptyList();
        }
        return Arrays.asList(meta.obfuscationSteps());
    }

    /**
     * 获取所有已注册 Packer 的混淆步骤声明映射，供 /supported-types 接口使用。
     * <p>
     * 返回：packer 名称 -> 支持的步骤 ID 列表（空列表表示不支持混淆层配置）。
     */
    public static Map<String, List<String>> getPackerObfuscationStepsMap() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (PackerMeta meta : getSortedMetadataSnapshot()) {
            result.put(meta.name(),
                meta.obfuscationSteps().length == 0
                    ? Collections.emptyList()
                    : Arrays.asList(meta.obfuscationSteps()));
        }
        return result;
    }

    /**
     * 判断注册表中是否包含指定名称的 Packer
     */
    public static boolean contains(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        return REGISTRY.containsKey(normalize(name));
    }

    /** 返回延迟初始化状态，供诊断接口和前端展示。 */
    public static Map<String, Map<String, Object>> getAvailabilityMap() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (PackerMeta meta : getSortedMetadataSnapshot()) {
            Registration registration = REGISTRY.get(normalize(meta.name()));
            Map<String, Object> availability = new LinkedHashMap<String, Object>();
            availability.put("status", registration.status.getValue());
            availability.put("implementationClass", registration.packerType.getName());
            availability.put("failureReason", registration.failureReason);
            result.put(meta.name(), availability);
        }
        for (Map.Entry<String, String> failure : SCAN_FAILURES.entrySet()) {
            Map<String, Object> availability = new LinkedHashMap<String, Object>();
            availability.put("status", PackerStatus.FAILED.getValue());
            availability.put("implementationClass", failure.getKey());
            availability.put("failureReason", failure.getValue());
            result.put(failure.getKey(), availability);
        }
        return result;
    }

    private static List<PackerMeta> getSortedMetadataSnapshot() {
        List<PackerMeta> sorted;
        synchronized (ORDERED) {
            sorted = new ArrayList<>(ORDERED);
        }
        sorted.sort(META_ORDER);
        return sorted;
    }

    private static Set<PackerCapability> resolveCapabilities(String name) {
        LinkedHashSet<PackerCapability> result = new LinkedHashSet<PackerCapability>();
        collectRequirements(name, result, new LinkedHashSet<String>(), new HashSet<String>());
        return result;
    }

    private static Set<String> resolveRequiredClasses(String name) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        collectRequirements(name, new LinkedHashSet<PackerCapability>(), result, new HashSet<String>());
        return result;
    }

    private static int resolveMinTargetJava(String name) {
        return resolveMinTargetJava(name, new HashSet<String>());
    }

    private static int resolveMinTargetJava(String name, Set<String> visiting) {
        String key = normalize(name);
        if (!visiting.add(key)) {
            return 0;
        }
        PackerMeta meta = META.get(key);
        if (meta == null) {
            return 0;
        }
        int minimum = meta.minTargetJava();
        for (String dependency : meta.dependencies()) {
            minimum = Math.max(minimum, resolveMinTargetJava(dependency, visiting));
        }
        return minimum;
    }

    private static Set<String> resolveMissingDependencies(String name) {
        LinkedHashSet<String> missing = new LinkedHashSet<String>();
        collectMissingDependencies(name, missing, new HashSet<String>());
        return missing;
    }

    private static void collectMissingDependencies(String name,
                                                   Set<String> missing,
                                                   Set<String> visiting) {
        String key = normalize(name);
        if (!visiting.add(key)) {
            return;
        }
        PackerMeta meta = META.get(key);
        if (meta == null) {
            missing.add(name);
            return;
        }
        for (String dependency : meta.dependencies()) {
            collectMissingDependencies(dependency, missing, visiting);
        }
    }

    private static void collectRequirements(String name,
                                            Set<PackerCapability> capabilities,
                                            Set<String> requiredClasses,
                                            Set<String> visiting) {
        String key = normalize(name);
        if (!visiting.add(key)) {
            return;
        }
        PackerMeta meta = META.get(key);
        if (meta == null) {
            return;
        }
        capabilities.addAll(Arrays.asList(meta.requiredCapabilities()));
        requiredClasses.addAll(Arrays.asList(meta.requiredClasses()));
        for (String dependency : meta.dependencies()) {
            collectRequirements(dependency, capabilities, requiredClasses, visiting);
        }
    }

    private static List<String> capabilityValues(Set<PackerCapability> capabilities) {
        List<String> result = new ArrayList<String>();
        for (PackerCapability capability : capabilities) {
            result.add(capability.getValue());
        }
        return result;
    }

    private static final class Registration {
        private final Class<? extends Packer> packerType;
        private volatile Packer instance;
        private volatile PackerStatus status;
        private volatile String failureReason;

        private Registration(Class<? extends Packer> packerType, Packer instance) {
            this.packerType = packerType;
            this.instance = instance;
            this.status = instance == null ? PackerStatus.UNINITIALIZED : PackerStatus.AVAILABLE;
        }

        private Packer getOrCreate() {
            Packer current = instance;
            if (current != null) {
                return current;
            }
            if (status == PackerStatus.FAILED) {
                throw initializationFailure();
            }
            synchronized (this) {
                if (instance != null) {
                    return instance;
                }
                if (status == PackerStatus.FAILED) {
                    throw initializationFailure();
                }
                try {
                    Packer created = packerType.getDeclaredConstructor().newInstance();
                    instance = created;
                    status = PackerStatus.AVAILABLE;
                    failureReason = null;
                    return created;
                } catch (Throwable throwable) {
                    Throwable cause = throwable instanceof java.lang.reflect.InvocationTargetException
                            && throwable.getCause() != null
                            ? throwable.getCause()
                            : throwable;
                    failureReason = failureMessage(cause);
                    status = PackerStatus.FAILED;
                    throw initializationFailure();
                }
            }
        }

        private void setInstance(Packer packer) {
            synchronized (this) {
                instance = packer;
                status = PackerStatus.AVAILABLE;
                failureReason = null;
            }
        }

        private IllegalStateException initializationFailure() {
            return new IllegalStateException("Packer [" + packerType.getName()
                    + "] 初始化失败: " + failureReason);
        }
    }

    private static String failureMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
