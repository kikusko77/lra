package io.narayana.lra.coordinator.failureflags;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FailureFlags {

    public enum FailurePoint {
        START,
        JOIN_BEFORE_SAVE,
        JOIN_AFTER_SAVE,
        JOIN_BEFORE_DEACTIVATE,
        JOIN_AFTER_DEACTIVATE,
        JOIN_BEFORE_RESPONSE,
        JOIN_AFTER_RESPONSE_APPEND,
        END_BEFORE_SAVE,
        END_AFTER_SAVE,
        END_DURING_CLEANUP,
        END_AFTER_CLEANUP,
        END_AFTER_PARTICIPANT_RESPONSE,
        LEAVE_BEFORE_SAVE,
        LEAVE_AFTER_SAVE;
    }

    private static final Map<FailurePoint, AtomicBoolean> FLAGS = new EnumMap<>(FailurePoint.class);

    static {
        for (FailurePoint p : FailurePoint.values())
            FLAGS.put(p, new AtomicBoolean(false));
    }

    private FailureFlags() {
    }

    public static void set(FailurePoint p, boolean enabled) {
        FLAGS.get(p).set(enabled);
    }

    public static boolean isEnabled(FailurePoint p) {
        return FLAGS.get(p).get();
    }

    public static void exitIfEnabled(FailurePoint p) {
        if (isEnabled(p)) {
            Runtime.getRuntime().halt(1);
        }
    }

    public static void resetAll() {
        FLAGS.values().forEach(b -> b.set(false));
    }
}
