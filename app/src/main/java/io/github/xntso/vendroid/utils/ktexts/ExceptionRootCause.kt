package io.github.xntso.vendroid.utils.ktexts

val Throwable.rootCause: Throwable
    get() {
        var cause = this
        while (cause.cause != null) {
            cause = cause.cause!!
        }
        return cause
    }