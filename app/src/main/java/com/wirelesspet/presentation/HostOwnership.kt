package com.wirelesspet.presentation

/** 启动请求尚无 owner；实际宿主认领后，只有同一实例能更新或释放槽位。 */
object HostOwnership {
    fun canClaim(host: ActiveHost?, kind: HostKind, token: String): Boolean =
        host == null || host.kind == kind &&
            (host.ownerToken == null || host.ownerToken == token)

    fun owns(host: ActiveHost?, kind: HostKind, token: String): Boolean =
        host != null && host.kind == kind && host.ownerToken == token
}
