package app.jiti.controlcenter.core;

/** Classifies transport failures before any automatic recovery is considered. */
public final class RecoveryErrorClassifier {
    public enum TransportFailure {
        NETWORK_UNAVAILABLE,
        DNS_FAILURE,
        CONNECT_TIMEOUT,
        READ_TIMEOUT,
        CONNECTION_RESET,
        TLS_CERTIFICATE,
        INVALID_ENDPOINT,
        UNKNOWN
    }

    private RecoveryErrorClassifier() {}

    public static RecoveryPolicy.ErrorClass fromHttpStatus(int status) {
        if (status == 429) return RecoveryPolicy.ErrorClass.RATE_LIMITED;
        if (status == 401) return RecoveryPolicy.ErrorClass.AUTHENTICATION;
        // 403 includes policy/region denial (for example unsupported territory).
        // Retrying it blindly cannot repair the policy and can amplify outages.
        if (status == 403) return RecoveryPolicy.ErrorClass.REMOTE_POLICY;
        if (status == 408 || status == 425 || (status >= 500 && status <= 599)) {
            return RecoveryPolicy.ErrorClass.TRANSIENT;
        }
        if (status == 400 || status == 404 || status == 409 || status == 422) {
            return RecoveryPolicy.ErrorClass.CONFIGURATION;
        }
        return RecoveryPolicy.ErrorClass.UNKNOWN;
    }

    public static RecoveryPolicy.ErrorClass fromTransportFailure(TransportFailure failure) {
        if (failure == null) return RecoveryPolicy.ErrorClass.UNKNOWN;
        switch (failure) {
            case NETWORK_UNAVAILABLE:
            case DNS_FAILURE:
            case CONNECT_TIMEOUT:
            case READ_TIMEOUT:
            case CONNECTION_RESET:
                return RecoveryPolicy.ErrorClass.TRANSIENT;
            case TLS_CERTIFICATE:
            case INVALID_ENDPOINT:
                return RecoveryPolicy.ErrorClass.CONFIGURATION;
            default:
                return RecoveryPolicy.ErrorClass.UNKNOWN;
        }
    }

    /**
     * Applies a server Retry-After duration when present, otherwise the
     * locally calculated backoff. Values are clamped to the configured cap.
     * The caller is responsible for parsing the HTTP header without exposing it.
     */
    public static long boundedRetryAfterMillis(long retryAfterMillis,
                                               long fallbackDelayMillis,
                                               long maximumDelayMillis) {
        if (fallbackDelayMillis < 0 || maximumDelayMillis < 0) {
            throw new IllegalArgumentException("retry delays must be non-negative");
        }
        long selected = retryAfterMillis >= 0 ? retryAfterMillis : fallbackDelayMillis;
        return Math.min(selected, maximumDelayMillis);
    }
}
