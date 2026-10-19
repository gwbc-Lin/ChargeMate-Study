package com.wirelesspet.pet

import org.junit.Assert.*
import org.junit.Test

class OfflineAssetPolicyTest {

    @Test fun `拒绝网络站点和文件协议`() {
        assertNull(OfflineAssetPolicy.path("https", "example.com", "/live2d/index.html"))
        assertNull(OfflineAssetPolicy.path("file", null, "/live2d/index.html"))
        assertNull(OfflineAssetPolicy.path("http", "local.wirelesspet", "/live2d/index.html"))
    }
    @Test fun `拒绝解码后的路径穿越和目录外文件`() {
        listOf("/live2d/../secret", "/live2d/./index.html", "/live2d/..\\secret", "/secret", "/live2d-other/index.html").forEach {
            assertNull(OfflineAssetPolicy.path("https", "local.wirelesspet", it))
        }
    }

}
