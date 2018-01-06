package net.openhft.chronicle.engine.api.query;

import net.openhft.chronicle.wire.Marshallable;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Rob Austin.
 */
public class GenericTypesToString implements TypeToString {

    private final Map<String, Class> cache1 = new ConcurrentHashMap<>();
    private final Map<Class, String> cache2 = new ConcurrentHashMap<>();

    public GenericTypesToString(@NotNull Class... clazzes) {

        for (@NotNull Class clazz : clazzes) {

            for (@NotNull Method m : clazz.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()))
                    continue;

                if (m.getParameterCount() == 1) {
                    Class<?> c = m.getParameterTypes()[0];
                    Class old = cache1.put(m.getName().intern(), c);
                    if (old != null) {
                        throw new IllegalStateException("name=" + m.getName() + " is already " +
                                "associated with " + old + ", " +
                                "you can't " +
                                "re-associate it with " + c.getSimpleName());
                    }

                    String oldName = cache2.put(c, m.getName());

                    if (oldName != null) {
                        throw new IllegalStateException("name=" + c.getSimpleName() + " is already " +
                                "associated with " + oldName + ", " +
                                "you can't " +
                                "re-associate it with " + m.getName());
                    }

                }

            }
        }
    }

    @Override
    public String typeToString(Class type) {
        return cache2.get(type);
    }

    @Override
    public Class<? extends Marshallable> toType(@NotNull CharSequence type) {
        return cache1.get(type.toString());
    }

}