package app.buddy.assistant;

/** Small fail-closed lifecycle state machine for one supervised process. */
final class RuntimeStateMachine {
    enum State { STOPPED, STARTING, READY, UNHEALTHY, STOPPING }

    private State state = State.STOPPED;

    synchronized State state() { return state; }

    synchronized void transition(State next) {
        if (next == null) throw new IllegalArgumentException("next state is required");
        boolean allowed =
                (state == State.STOPPED && next == State.STARTING)
                || (state == State.STARTING && (next == State.READY || next == State.UNHEALTHY || next == State.STOPPING))
                || (state == State.READY && (next == State.UNHEALTHY || next == State.STOPPING))
                || (state == State.UNHEALTHY && (next == State.STARTING || next == State.STOPPING || next == State.STOPPED))
                || (state == State.STOPPING && (next == State.STOPPED || next == State.UNHEALTHY));
        if (!allowed) throw new IllegalStateException("Illegal runtime transition: " + state + " -> " + next);
        state = next;
    }
}
