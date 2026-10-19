package com.wirelesspet.pet

import org.junit.Assert.assertEquals
import org.junit.Test

class SpineImportPolicyTest {

    @Test fun multiPageAtlas() {
        SpineImportPolicy.checkVersion("4.1.20")
        org.junit.Assert.assertTrue(runCatching { SpineImportPolicy.checkVersion("4.2.0") }.isFailure)
        assertEquals(listOf("one.png", "textures/two.png"), SpineImportPolicy.pages("\n one.png\nsize: 512,512\npart\n  rotate: false\n\ntextures/two.png\nsize: 512,512\nother\n  index: -1\n"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTraversal() {
        SpineImportPolicy.pages("../outside.png\nsize: 512,512")
    }
}
