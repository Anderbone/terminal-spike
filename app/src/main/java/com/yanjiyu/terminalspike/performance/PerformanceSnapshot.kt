package com.yanjiyu.terminalspike.performance

data class FrameTimingSnapshot(
    val drawsPerSecond: Double = 0.0,
    val averageDrawMs: Double = 0.0,
    val p95DrawMs: Double = 0.0,
    val drawsSlowerThan8Ms: Int = 0,
    val drawsSlowerThan16Ms: Int = 0,
    val idle: Boolean = true,
)

data class PerformanceSnapshot(
    val timing: FrameTimingSnapshot = FrameTimingSnapshot(),
    val scrollbackLines: Int = 0,
    val visibleLines: Int = 0,
    val pendingOutputLines: Int = 0,
    val heapMegabytes: Double = 0.0,
    val autoFollow: Boolean = true,
    val workload: String = "Stream",
    val rate: String = "Stopped",
)
