package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

@Serializable
public enum class Severity {
    /** Fails the build. Printed to stderr. */
    ERROR,

    /** Printed to stdout, build continues. */
    WARNING,

    /** Silently collected, visible in report only. */
    INFO,
}
