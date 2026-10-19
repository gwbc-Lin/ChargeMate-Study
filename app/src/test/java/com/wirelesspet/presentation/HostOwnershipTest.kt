package com.wirelesspet.presentation

import com.wirelesspet.policy.PresentationSource
import org.junit.Assert.*
import org.junit.Test

class HostOwnershipTest {
    private fun host(kind: HostKind = HostKind.ACTIVITY, token: String? = "activity-1") = ActiveHost(
        kind, PresentationSource.PREVIEW, null, true, 100L, token,
    )

    @Test fun `屏保和页面不能互相认领槽位`() {
        assertFalse(HostOwnership.canClaim(host(), HostKind.DREAM, "dream-1"))
        assertFalse(HostOwnership.canClaim(host(HostKind.DREAM, "dream-1"), HostKind.ACTIVITY, "activity-1"))
    }

    @Test fun `旧页面实例不能释放新页面槽位`() {
        assertFalse(HostOwnership.owns(host(token = "activity-2"), HostKind.ACTIVITY, "activity-1"))
        assertFalse(HostOwnership.canClaim(host(token = "activity-2"), HostKind.ACTIVITY, "activity-1"))
        assertTrue(HostOwnership.owns(host(), HostKind.ACTIVITY, "activity-1"))
    }

}
