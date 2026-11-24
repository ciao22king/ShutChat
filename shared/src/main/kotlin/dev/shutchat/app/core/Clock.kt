package dev.shutchat.app.core

/**
 * Injectable time source. Everything that reasons about invite expiry goes
 * through this so expiry behaviour is testable without sleeping.
 */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        /** Production clock. Tests inject their own. */
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
