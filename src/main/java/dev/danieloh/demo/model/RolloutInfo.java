package dev.danieloh.demo.model;

public record RolloutInfo(
    String name,
    String phase,
    Integer canaryWeight,
    Integer stableWeight,
    String message,
    AnalysisInfo analysis,
    Integer currentStepIndex,
    String image
) {
    public static RolloutInfo notFound() {
        return new RolloutInfo(
            "N/A",
            "NotFound",
            0,
            100,
            "No active rollout found",
            null,
            null,
            null
        );
    }
}
