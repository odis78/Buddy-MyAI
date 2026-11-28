package app.buddy.assistant;

/** Starts each optional MCP helper independently after the shared runtime is ready. */
final class RuntimeHelperBootstrap {
    interface StateWriter {
        void write(String state, String detail);
    }

    interface DetailProvider {
        String get();
    }

    private RuntimeHelperBootstrap() { }

    static boolean startOne(RuntimeLifecycleGate lifecycle,
                            RuntimeLifecycleGate.StartAction action,
                            StateWriter stateWriter,
                            String readyDetail,
                            DetailProvider waitingDetail) {
        try {
            Boolean ready = lifecycle.startIfActive(action);
            if (ready == null) return false;
            boolean recorded = lifecycle.runIfActive(() ->
                    stateWriter.write(ready ? "READY" : "WAITING",
                            ready ? readyDetail : safeDetail(waitingDetail)));
            return recorded && ready;
        } catch (Exception e) {
            lifecycle.runIfActive(() -> stateWriter.write("ERROR", safeMessage(e)));
            return false;
        }
    }

    private static String safeDetail(DetailProvider provider) {
        if (provider == null) return "Helper is not ready";
        try {
            String detail = provider.get();
            return detail == null || detail.trim().isEmpty() ? "Helper is not ready" : detail;
        } catch (RuntimeException ignored) {
            return "Helper is not ready";
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
