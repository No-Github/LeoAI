package org.leo.jmg.mem.packer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Packer 与目标运行环境的兼容性评估结果。
 */
public final class PackerCompatibilityResult {
    private final List<String> errors = new ArrayList<String>();
    private final List<String> warnings = new ArrayList<String>();

    void addError(String message) {
        errors.add(message);
    }

    void addWarning(String message) {
        warnings.add(message);
    }

    public boolean isSupported() {
        return errors.isEmpty();
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public void throwIfUnsupported() {
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(join(errors));
        }
    }

    private static String join(List<String> messages) {
        StringBuilder result = new StringBuilder();
        for (String message : messages) {
            if (result.length() > 0) {
                result.append("；");
            }
            result.append(message);
        }
        return result.toString();
    }
}
