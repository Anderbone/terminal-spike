package com.yanjiyu.terminalspike.workload

enum class WorkloadMode(val label: String) {
    STREAM("Stream"),
    FULL_SCREEN("Full screen"),
}

enum class StreamingRate(val linesPerSecond: Int, val label: String) {
    STOPPED(0, "Stopped"),
    LINES_10(10, "10 lines/s"),
    LINES_100(100, "100 lines/s"),
    LINES_1K(1_000, "1,000 lines/s"),
    LINES_5K(5_000, "5,000 lines/s"),
}

enum class FullScreenRate(val updatesPerSecond: Int, val label: String) {
    FPS_1(1, "1 update/s"),
    FPS_10(10, "10 updates/s"),
    FPS_30(30, "30 updates/s"),
    FPS_60(60, "60 updates/s"),
}

enum class PreloadSize(val lineCount: Int, val label: String) {
    LINES_1K(1_000, "1,000"),
    LINES_10K(10_000, "10,000"),
    LINES_50K(50_000, "50,000"),
    LINES_100K(100_000, "100,000"),
}
