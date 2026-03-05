package io.narayana.lra.coordinator.injectflags;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InjectFlags {

    public enum InjectPoint {
        START,
        JOIN_BEFORE_SAVE,
        JOIN_AFTER_SAVE,
        CLOSE,
        CANCEL
    }

    private static final Map<InjectPoint, AtomicBoolean> FLAGS = new EnumMap<>(InjectPoint.class);

    static {
        for (InjectPoint p : InjectPoint.values())
            FLAGS.put(p, new AtomicBoolean(false));
    }

    private InjectFlags() {
    }

    public static void set(InjectPoint p, boolean enabled) {
        FLAGS.get(p).set(enabled);
    }

    public static boolean isEnabled(InjectPoint p) {
        return FLAGS.get(p).get();
    }

    public static void resetAll() {
        FLAGS.values().forEach(b -> b.set(false));
    }
}