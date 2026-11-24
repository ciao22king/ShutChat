package dev.shutchat.app.p2p.call

/**
 * ICE servers used to open a media path between two devices behind NAT.
 *
 * STUN is enough for most home and mobile networks: it only discovers the
 * public address each side is seen on. When both peers sit behind symmetric NAT
 * (some carriers, some corporate networks) a relay is required — that is what
 * TURN is — and the user can add one from settings. The default list is
 * deliberately public and account-free, matching the rest of the app.
 */
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

object CallIceServers {

    val default: List<IceServerConfig> = listOf(
        IceServerConfig(
            urls = listOf(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302",
            ),
        ),
    )
}
