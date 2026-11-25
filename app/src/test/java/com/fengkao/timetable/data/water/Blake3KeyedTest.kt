package com.fengkao.timetable.data.water

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Blake3KeyedTest {

    private val officialKey = "whats the Elvish word for friend".toByteArray(Charsets.UTF_8)

    private fun hashOf(n: Int): String {
        val input = ByteArray(n) { (it % 251).toByte() }
        return Blake3Keyed.keyedHashHex(officialKey, input)
    }

    @Test
    fun `matches official keyed blake3 vectors across chunk boundaries`() {
        assertEquals("92b2b75604ed3c761f9d6f62392c8a9227ad0ea3f09573e783f1498a4ed60d26", hashOf(0))
        assertEquals("6d7878dfff2f485635d39013278ae14f1454b8c0a3a2d34bc1ab38228a80c95b", hashOf(1))
        assertEquals("bb1eb5d4afa793c1ebdd9fb08def6c36d10096986ae0cfe148cd101170ce37ae", hashOf(63))
        assertEquals("ba8ced36f327700d213f120b1a207a3b8c04330528586f414d09f2f7d9ccb7e6", hashOf(64))
        assertEquals("75c46f6f3d9eb4f55ecaaee480db732e6c2105546f1e675003687c31719c7ba4", hashOf(1024))
        assertEquals("befc660aea2f1718884cd8deb9902811d332f4fc4a38cf7c7300d597a081bfc0", hashOf(4096))
    }

    @Test
    fun `rejects non-32-byte keys`() {
        assertThrows(IllegalArgumentException::class.java) {
            Blake3Keyed.keyedHash(ByteArray(31), byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Blake3Keyed.keyedHash(ByteArray(33), byteArrayOf(1))
        }
    }

    @Test
    fun `matches locked 3_1_10 WaterLoginSigner test vector`() {
        assertEquals(1793849880L, WaterLoginSigner.windowValue(1793849886000L))
        assertEquals(1793849880L, WaterLoginSigner.windowValue(1793849880000L))
        assertEquals(1793849850L, WaterLoginSigner.windowValue(1793849879999L))
        assertEquals("acclogin", WaterLoginSigner.pathToken())
        assertEquals("ogincode", WaterLoginSigner.pathToken("/api/v1/acc/login/code"))

        val sign = WaterLoginSigner.sign("13800138000", "123456", 1793849886000L)
        assertEquals("1e8502705cab835610587ecddbf5600e", sign)

        val prevBucket = WaterLoginSigner.sign("13800138000", "123456", 1793849886000L - 30_000L)
        assertNotEquals(sign, prevBucket)
        assertTrue(sign.matches(Regex("^[0-9a-f]{32}$")))
    }
}
