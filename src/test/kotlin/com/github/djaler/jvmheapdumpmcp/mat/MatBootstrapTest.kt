package com.github.djaler.jvmheapdumpmcp.mat

import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.Test

class MatBootstrapTest {

    @Test
    fun `initialize should complete without error`() {
        assertDoesNotThrow<Unit> { MatBootstrap.initialize() }
    }

    @Test
    fun `initialize should be idempotent when called multiple times`() {
        assertDoesNotThrow<Unit> {
            MatBootstrap.initialize()
            MatBootstrap.initialize()
        }
    }
}
