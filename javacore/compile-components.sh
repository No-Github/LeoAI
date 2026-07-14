#!/usr/bin/env bash
# 用 Java 8 的 javac 以 -source 1.6 -target 1.6 编译所有 component 类
# 生成 major version 50 (Java 6) 的 .payload 放入 src/main/resources/component/
#
# 用法:
#   cd LeoAI/javacore && bash compile-components.sh          # 审计并更新 payload
#   cd LeoAI/javacore && bash compile-components.sh --check  # 仅审计，不写 resources
#
# 要求 Java 8（Zulu 8 / OpenJDK 8 等）；脚本会优先使用 JAVA8_HOME，
# 并在 macOS 上自动尝试 /usr/libexec/java_home -v 1.8。
# -source 1.6 限制语言特性（无 lambda/diamond/try-with-resources）
# -target 1.6 生成 major version 50 字节码
# CloneWithJavassist.setVersionToJava5() 运行时会进一步将版本号降为 49

set -euo pipefail

MODE="${1:-}"
if [ -n "$MODE" ] && [ "$MODE" != "--check" ]; then
    echo "用法: $0 [--check]" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$SCRIPT_DIR/src/main/java/org/leo/core/component"
OUT_DIR="$SCRIPT_DIR/src/main/resources/component"
TMP_DIR=$(mktemp -d)
AUDIT_POM="$SCRIPT_DIR/component-api-audit/pom.xml"

cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

resolve_java8_home() {
    if [ -n "${JAVA8_HOME:-}" ] && [ -x "$JAVA8_HOME/bin/javac" ]; then
        printf '%s\n' "$JAVA8_HOME"
        return 0
    fi
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        local mac_home
        mac_home=$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)
        if [ -n "$mac_home" ] && [ -x "$mac_home/bin/javac" ]; then
            printf '%s\n' "$mac_home"
            return 0
        fi
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] \
            && "$JAVA_HOME/bin/javac" -version 2>&1 | grep -q 'javac 1\.8\.'; then
        printf '%s\n' "$JAVA_HOME"
        return 0
    fi
    if command -v javac >/dev/null 2>&1 \
            && javac -version 2>&1 | grep -q 'javac 1\.8\.'; then
        dirname "$(dirname "$(command -v javac)")"
        return 0
    fi
    return 1
}

JAVA8_HOME_RESOLVED=$(resolve_java8_home || true)
if [ -z "$JAVA8_HOME_RESOLVED" ]; then
    echo "错误: 未找到 Java 8。请设置 JAVA8_HOME 后重试。" >&2
    exit 1
fi
JAVAC="$JAVA8_HOME_RESOLVED/bin/javac"
JAVAP="$JAVA8_HOME_RESOLVED/bin/javap"

echo "=== 编译 component 类 (-source 1.6 -target 1.6) ==="
echo "javac: $($JAVAC -version 2>&1)"
echo "源码目录: $SRC_DIR"
echo "输出目录: $OUT_DIR"
echo "临时目录: $TMP_DIR"
echo ""

"$JAVAC" -source 1.6 -target 1.6 \
      -Xlint:-options \
      -d "$TMP_DIR" \
      "$SRC_DIR"/*.java

CLASS_DIR="$TMP_DIR/org/leo/core/component"
source_count=$(find "$SRC_DIR" -maxdepth 1 -type f -name '*.java' | wc -l | tr -d ' ')
class_count=$(find "$CLASS_DIR" -maxdepth 1 -type f -name '*.class' | wc -l | tr -d ' ')
if [ "$class_count" -ne "$source_count" ]; then
    echo "错误: 源文件数为 $source_count，但生成了 $class_count 个 class；请检查内部类或遗漏。" >&2
    exit 1
fi
if find "$CLASS_DIR" -maxdepth 1 -type f -name '*$*.class' | grep -q .; then
    echo "错误: 检测到额外的内部类 class，单文件 payload 无法加载。" >&2
    find "$CLASS_DIR" -maxdepth 1 -type f -name '*$*.class' -print >&2
    exit 1
fi

for classfile in "$CLASS_DIR"/*.class; do
    major=$($JAVAP -verbose "$classfile" | awk '/major version:/{print $3; exit}')
    if [ "$major" != "50" ]; then
        echo "错误: $(basename "$classfile") 的字节码版本为 $major，期望 50 (Java 6)。" >&2
        exit 1
    fi
    class_name=$(basename "$classfile" .class)
    allowed_name="org/leo/core/component/$class_name"
    foreign_refs=$($JAVAP -verbose "$classfile" \
        | sed -n 's|.*// \(org/leo/core/component/[A-Za-z0-9_$]*\).*|\1|p' \
        | sort -u \
        | grep -v "^${allowed_name}$" || true)
    if [ -n "$foreign_refs" ]; then
        echo "错误: $class_name 直接引用了其他 Component，单 class payload 无法独立加载:" >&2
        echo "$foreign_refs" >&2
        exit 1
    fi
done

echo "=== 检查 Java 6 API 兼容性 ==="
"$REPO_DIR/mvnw" -q -f "$AUDIT_POM" \
    -Dcomponent.classes.dir="$TMP_DIR" \
    org.codehaus.mojo:animal-sniffer-maven-plugin:1.27:check

if [ "$MODE" = "--check" ]; then
    echo ""
    echo "=== 检查完成: ${class_count} 个 Component 均通过，未更新 payload ==="
    exit 0
fi

echo "=== 拷贝 .payload 文件 ==="
mkdir -p "$OUT_DIR"

count=0
for classfile in "$CLASS_DIR"/*.class; do
    filename=$(basename "$classfile" .class)
    cp "$classfile" "$OUT_DIR/${filename}.payload"
    echo "  $filename.payload"
    count=$((count + 1))
done

echo ""
echo "=== 完成: ${count} 个 .payload 已更新到 $OUT_DIR ==="
