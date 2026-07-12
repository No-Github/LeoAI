package org.leo.jmg.mem.packer;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于类路径扫描发现所有带 {@link PackerMeta} 注解的 {@link Packer} 实现。
 * <p>
 * 替代 ServiceLoader + META-INF/services 的冗余机制：
 * 新增 Packer 只需加 {@code @PackerMeta} 注解，无需手动维护 services 文件。
 */
final class PackerScanner {

    private static final String RESOURCE_PATTERN = "classpath*:org/leo/jmg/mem/packer/**/*.class";

    private PackerScanner() {
    }

    /**
     * 扫描 base package 下所有带 @PackerMeta 注解的 Packer 实现类并实例化
     */
    static List<Packer> scan() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = PackerScanner.class.getClassLoader();
        }
        return scan(resolver, classLoader);
    }

    /**
     * 使用指定资源解析器和类加载器执行扫描，便于在不同运行环境中复用和测试。
     */
    static List<Packer> scan(ResourcePatternResolver resolver, ClassLoader classLoader) {
        if (resolver == null) {
            throw new IllegalArgumentException("resolver 不能为空");
        }
        if (classLoader == null) {
            throw new IllegalStateException("无法获取用于扫描 Packer 的 ClassLoader");
        }

        Resource[] resources;
        try {
            resources = resolver.getResources(RESOURCE_PATTERN);
        } catch (IOException e) {
            throw new IllegalStateException("扫描 Packer 类路径失败: " + RESOURCE_PATTERN, e);
        }

        // classpath*: 可能从多个位置返回同一个类；先去重再排序，保证扫描行为稳定。
        Set<String> uniqueClassNames = new LinkedHashSet<String>();
        for (Resource resource : resources) {
            String className = resolveClassName(resource);
            if (className == null || className.indexOf('$') >= 0) {
                continue;
            }
            uniqueClassNames.add(className);
        }
        List<String> classNames = new ArrayList<String>(uniqueClassNames);
        Collections.sort(classNames);

        List<Packer> packers = new ArrayList<Packer>();
        for (String className : classNames) {
            try {
                Class<?> clazz = classLoader.loadClass(className);
                if (!Packer.class.isAssignableFrom(clazz)) {
                    continue;
                }
                if (clazz.isInterface() || java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                    continue;
                }
                if (clazz.getAnnotation(PackerMeta.class) == null) {
                    continue;
                }

                Packer instance = (Packer) clazz.getDeclaredConstructor().newInstance();
                packers.add(instance);
            } catch (ReflectiveOperationException | LinkageError | SecurityException e) {
                throw new IllegalStateException("加载 Packer 类失败: " + className, e);
            }
        }

        return packers;
    }

    /**
     * 从 Resource 推导全限定类名
     */
    static String resolveClassName(Resource resource) {
        if (resource == null) {
            return null;
        }
        try {
            String uri = resource.getURI().toString();
            // 定位 base package 路径片段
            String marker = "org/leo/jmg/mem/packer/";
            int idx = uri.lastIndexOf(marker);
            if (idx < 0) return null;

            String relative = uri.substring(idx);
            if (!relative.endsWith(".class")) return null;

            // 去掉 .class 后缀，路径分隔符转点号
            return relative.substring(0, relative.length() - 6).replace('/', '.');
        } catch (IOException e) {
            return null;
        }
    }
}
