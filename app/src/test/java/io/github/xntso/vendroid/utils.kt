package io.github.xntso.vendroid

import io.github.xntso.vendroid.plugins.telemetry.Telemetry

fun setUpMockTelemetry() {
    Telemetry.TESTS_ONLY_setTestMode(true)
}
