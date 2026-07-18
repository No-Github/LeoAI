package org.leo.core.component;

import org.junit.jupiter.api.Test;
import org.leo.core.util.javassist.CloneWithJavassist;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerManageComponentTest {

    @Test
    void transformedContainerPayloadsInitializeAfterMethodRandomization() throws Exception {
        assertTransformedRunnable("SpringFrameworkManageComponent");
        assertTransformedRunnable("TomcatCatalinaManageComponent");
        assertTransformedRunnable("WeblogicCatalinaManageComponent");
    }

    @Test
    void unknownOperationsReturnBadRequest() throws Exception {
        assertEquals(400, code(invoke(new SpringFrameworkManageComponent(), "unknown")));
        assertEquals(400, code(invoke(new TomcatCatalinaManageComponent(), "unknown")));
        assertEquals(400, code(invoke(new WeblogicCatalinaManageComponent(), "unknown")));
    }

    @Test
    void tomcatFieldWriterFindsInheritedFields() throws Exception {
        ChildHolder holder = new ChildHolder();
        TomcatCatalinaManageComponent.setFieldValue(holder, "value", "updated");
        assertEquals("updated", TomcatCatalinaManageComponent.getFV(holder, "value"));
    }

    @Test
    void weblogicFilterInfoUsesDeclaredServletName() {
        FakeFilterManager manager = new FakeFilterManager();
        manager.filters.put("sample", new FakeFilter("example.Filter"));
        manager.filterPatternList.add(new FakeFilterPattern("sample", "targetServlet", "/sample"));

        ArrayList filters = new WeblogicCatalinaManageComponent().getAllFilter(new FakeWeblogicContext(manager));

        assertEquals(1, filters.size());
        Map info = (Map) filters.get(0);
        assertEquals("targetServlet", info.get("servletName"));
        assertEquals("example.Filter", info.get("filterClass"));
    }

    @Test
    void weblogicFilterRemovalHandlesMultipleMappings() throws Exception {
        FakeFilterManager manager = new FakeFilterManager();
        manager.filters.put("remove", new FakeFilter("example.Remove"));
        manager.filters.put("keep", new FakeFilter("example.Keep"));
        manager.filterPatternList.add(new FakeFilterPattern("remove", "a", "/a"));
        manager.filterPatternList.add(new FakeFilterPattern("remove", "b", "/b"));
        manager.filterPatternList.add(new FakeFilterPattern("keep", "c", "/c"));

        Method remove = WeblogicCatalinaManageComponent.class.getDeclaredMethod(
                "removeFilter", Object.class, String.class);
        remove.setAccessible(true);
        remove.invoke(new WeblogicCatalinaManageComponent(), manager, "remove");

        assertFalse(manager.filters.containsKey("remove"));
        assertEquals(1, manager.filterPatternList.size());
        assertEquals("keep", manager.filterPatternList.get(0).getFilterName());
    }

    private Map<String, Object> invoke(Object component, Object methodName) throws Exception {
        HashMap<String, Object> params = new HashMap<>();
        params.put("methodName", methodName);
        HashMap<String, Object> results = new HashMap<>();
        setField(component, "params", params);
        setField(component, "results", results);
        component.getClass().getDeclaredMethod("invoke").invoke(component);
        return results;
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void assertTransformedRunnable(String componentId) throws Exception {
        String className = "org.leo.generated." + componentId + System.nanoTime();
        byte[] bytecode = CloneWithJavassist.cloneClass(componentId, className);
        Class<?> transformed = new BytecodeLoader().define(className, bytecode);
        assertTrue(Runnable.class.isAssignableFrom(transformed));
        assertTrue(transformed.getDeclaredConstructor().newInstance() instanceof Runnable);
    }

    private static class ParentHolder {
        private String value = "original";
    }

    private static final class ChildHolder extends ParentHolder {
    }

    private static final class FakeWeblogicContext {
        private final FakeFilterManager filterManager;

        private FakeWeblogicContext(FakeFilterManager filterManager) {
            this.filterManager = filterManager;
        }

        public Object getFilterManager() {
            return filterManager;
        }
    }

    private static final class FakeFilterManager {
        private final HashMap filters = new HashMap();
        private final ArrayList<FakeFilterPattern> filterPatternList = new ArrayList<>();
    }

    private static final class FakeFilter {
        private final String filterClassName;

        private FakeFilter(String filterClassName) {
            this.filterClassName = filterClassName;
        }
    }

    private static final class FakeFilterPattern {
        private final String filterName;
        private final String servletName;
        private final FakePatternMap map;

        private FakeFilterPattern(String filterName, String servletName, String pattern) {
            this.filterName = filterName;
            this.servletName = servletName;
            this.map = new FakePatternMap(pattern);
        }

        public String getFilterName() {
            return filterName;
        }

        public String getServletName() {
            return servletName;
        }

        public Object getMap() {
            return map;
        }
    }

    private static final class FakePatternMap {
        private final String pattern;

        private FakePatternMap(String pattern) {
            this.pattern = pattern;
        }

        public Object keys() {
            return new String[]{pattern};
        }
    }

    private static final class BytecodeLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
