package com.yanjiyu.terminalspike.performance

data class FrameTimingSnapshot(
    val fps: Double = 0.0,
    val averageFrameMs: Double = 0.0,
    val p95FrameMs: Double = 0.0,
    val slowerThan8Ms: Int = 0,
    val slowerThan16Ms: Int = 0,
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
