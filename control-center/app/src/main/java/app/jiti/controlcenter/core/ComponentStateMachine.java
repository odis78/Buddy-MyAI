package app.jiti.controlcenter.core;

import java.util.EnumSet;
import java.util.Map;
import java.util.EnumMap;

public final class ComponentStateMachine {
    private static final Map<ComponentStatus, EnumSet<ComponentStatus>> ALLOWED =
            new EnumMap<>(ComponentStatus.class);

    static {
        allow(ComponentStatus.UNKNOWN, ComponentStatus.CHECKING, ComponentStatus.AVAILABLE, ComponentStatus.UNAVAILABLE, ComponentStatus.BLOCKED);
        allow(ComponentStatus.CHECKING, ComponentStatus.AVAILABLE, ComponentStatus.PID_PRESENT, ComponentStatus.RUNNING, ComponentStatus.READY, ComponentStatus.STOPPED,
                ComponentStatus.NOT_READY, ComponentStatus.ERROR, ComponentStatus.UNAVAILABLE, ComponentStatus.BLOCKED);
        allow(ComponentStatus.STOPPED, ComponentStatus.CHECKING, ComponentStatus.STARTING,
                ComponentStatus.UNAVAILABLE, ComponentStatus.BLOCKED);
        allow(ComponentStatus.STARTING, ComponentStatus.AVAILABLE, ComponentStatus.PID_PRESENT, ComponentStatus.RUNNING, ComponentStatus.READY, ComponentStatus.NOT_READY,
                ComponentStatus.ERROR, ComponentStatus.STOPPED, ComponentStatus.UNAVAILABLE, ComponentStatus.BLOCKED);
        allow(ComponentStatus.AVAILABLE, ComponentStatus.CHECKING, ComponentStatus.PID_PRESENT, ComponentStatus.RUNNING, ComponentStatus.READY, ComponentStatus.UNAVAILABLE, ComponentStatus.BLOCKED);
        allow(ComponentStatus.PID_PRESENT, ComponentStatus.CHECKING, ComponentStatus.RUNNING, ComponentStatus.READY, ComponentStatus.STOPPED, ComponentStatus.NOT_READY, ComponentStatus.ERROR, ComponentStatus.UNAVAILABLE, ComponentStatus.BLOCKED);
        allow(ComponentStatus.RUNNING, ComponentStatus.CHECKING, ComponentStatus.PID_PRESENT, ComponentStatus.READY, ComponentStatus.STOPPED,
                ComponentStatus.NOT_READY, ComponentStatus.ERROR, ComponentStatus.UNAVAILABLE, ComponentStatus.BLOCKED);
        allow(ComponentStatus.READY, ComponentStatus.CHECKING, ComponentStatus.NOT_READY, ComponentStatus.ERROR, ComponentStatus.UNAVAILABLE, ComponentStatus.STOPPED, ComponentStatus.BLOCKED);
        allow(ComponentStatus.NOT_READY, ComponentStatus.CHECKING, ComponentStatus.STARTING,
                ComponentStatus.PID_PRESENT, ComponentStatus.RUNNING, ComponentStatus.READY, ComponentStatus.ERROR, ComponentStatus.UNAVAILABLE, ComponentStatus.BLOCKED);
        allow(ComponentStatus.ERROR, ComponentStatus.CHECKING, ComponentStatus.STARTING,
                ComponentStatus.STOPPED, ComponentStatus.BLOCKED, ComponentStatus.UNAVAILABLE);
        allow(ComponentStatus.UNAVAILABLE, ComponentStatus.CHECKING, ComponentStatus.BLOCKED);
        allow(ComponentStatus.BLOCKED, ComponentStatus.CHECKING, ComponentStatus.STOPPED);
    }

    private ComponentStateMachine() {}

    private static void allow(ComponentStatus from, ComponentStatus... to) {
        EnumSet<ComponentStatus> values = EnumSet.noneOf(ComponentStatus.class);
        java.util.Collections.addAll(values, to);
        ALLOWED.put(from, values);
    }

    public static ComponentStatus transition(ComponentStatus current, ComponentStatus next) {
        if (current == null || next == null) throw new IllegalArgumentException("states must not be null");
        if (current == next) return next;
        EnumSet<ComponentStatus> allowed = ALLOWED.get(current);
        if (allowed == null || !allowed.contains(next))
            throw new IllegalStateException("invalid component transition: " + current + " -> " + next);
        return next;
    }
}
