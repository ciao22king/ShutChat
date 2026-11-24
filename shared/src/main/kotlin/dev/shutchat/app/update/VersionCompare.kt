package dev.shutchat.app.update

/**
 * Version ordering for the update check.
 *
 * Deliberately not a semver parser: the app's own versions are plain
 * `major.minor.patch` and the only question ever asked of them is "is the remote
 * one newer than mine". Everything after a `-` is ignored, so a tag like
 * `v1.2.0-beta1` compares as `1.2.0` rather than being rejected as malformed.
 *
 * Kept free of Android and of any network type so it can be unit tested on the JVM.
 */
object VersionCompare {

    /**
     * @return negative if [left] is older, positive if newer, zero if equal.
     *         Missing components count as zero, so `1.2` equals `1.2.0`.
     */
    fun compare(left: String, right: String): Int {
        val a = parts(left)
        val b = parts(right)
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val diff = a.getOrElse(index) { 0 } - b.getOrElse(index) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    fun isNewer(remote: String, local: String): Boolean = compare(remote, local) > 0

    /**
     * Strips the decoration a release tag may carry, such as a leading `v`.
     *
     * @return the numeric prefix, or null when there is nothing numeric to read.
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        val head = trimmed.substringBefore('-').substringBefore('+')
        val numbers = head.split('.').mapNotNull { it.toIntOrNull() }
        if (numbers.isEmpty()) return null
        return numbers.joinToString(".")
    }

    private fun parts(raw: String): List<Int> = normalize(raw)?.split('.')?.mapNotNull { it.toIntOrNull() }.orEmpty()
}
