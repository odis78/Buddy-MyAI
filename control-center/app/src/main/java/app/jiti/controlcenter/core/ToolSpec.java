package app.jiti.controlcenter.core;

public final class ToolSpec {
    public enum Risk { READ_ONLY, SENSITIVE_READ, INTERACTIVE, WRITE_OR_EXTERNAL, PRIVILEGED }
    public final String name;
    public final String group;
    public final boolean optionalFeature;
    public final Risk risk;

    public ToolSpec(String name, String group, boolean optionalFeature, Risk risk) {
        this.name = name;
        this.group = group;
        this.optionalFeature = optionalFeature;
        this.risk = risk;
    }
}
