package com.wirelesspet.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelImportPolicyTest {
    @Test fun `zip entries cannot escape or reference network locations`() {
        ModelImportPolicy.checkJsonDepth("{\"text\":\"[not nesting]\"}")
        assertThrows(IllegalArgumentException::class.java) { ModelImportPolicy.checkJsonDepth("[".repeat(49)) }
        listOf("../model.json", "a/../../model.json", "/model.json", "C:/model.json", "a\\model.json", "https://host/model.json").forEach {
            assertThrows(IllegalArgumentException::class.java) { ModelImportPolicy.entryPath(it) }
        }
    }

    @Test fun `references cannot escape package or redirect to remote resources`() {
        listOf("../../texture.png", "https://host/texture.png", "file:///texture.png", "/texture.png", "x.png?redirect=1", "x.png#part").forEach {
            assertThrows(IllegalArgumentException::class.java) { ModelImportPolicy.resourcePath("folder/model.json", it) }
        }
    }
}
