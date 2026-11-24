package app.jiti.controlcenter.termux;

/** Validates that a launcher status belongs to the current start request. */
public final class BuddyLaunchStatusPolicy {
    private BuddyLaunchStatusPolicy() {}

    public static boolean isFreshForRequest(long statusTimestampSeconds,
                                            long nowSeconds,
                                            long requestStartedAtSeconds) {
        return statusTimestampSeconds > 0
                && requestStartedAtSeconds > 0
                && statusTimestampSeconds >= requestStartedAtSeconds
                && nowSeconds >= statusTimestampSeconds
                && nowSeconds - statusTimestampSeconds <= 30L;
    }
}
