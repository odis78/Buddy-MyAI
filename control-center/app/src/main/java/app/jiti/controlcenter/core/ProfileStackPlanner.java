package app.jiti.controlcenter.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Validated order for the three REA tunnel profiles; Buddy is intentionally excluded. */
public final class ProfileStackPlanner {
    public static final String BRIDGE = "bridge";
    public static final String DROIDMCP = "droidmcp";
    public static final String REA_ANDROID = "rea-android";

    private static final List<String> START_ORDER = Collections.unmodifiableList(
            Arrays.asList(BRIDGE, DROIDMCP, REA_ANDROID));

    private ProfileStackPlanner() {}

    public static List<String> startOrder() {
        return START_ORDER;
    }

    public static List<String> stopOrder() {
        List<String> result = new ArrayList<>(START_ORDER);
        Collections.reverse(result);
        return Collections.unmodifiableList(result);
    }

    /** A full restart always stops in reverse order, then starts in forward order. */
    public static List<Step> restartOrder() {
        List<Step> result = new ArrayList<>();
        for (String profile : stopOrder()) result.add(new Step(profile, "stop"));
        for (String profile : startOrder()) result.add(new Step(profile, "start"));
        return Collections.unmodifiableList(result);
    }

    public static final class Step {
        public final String profileId;
        public final String operation;

        private Step(String profileId, String operation) {
            this.profileId = profileId;
            this.operation = operation;
        }
    }
}
