package com.fengkao.timetable.data.water

/**
 * Pure Kotlin keyed BLAKE3 hash (adapted from Kou-JunHao/Oasis-App, MIT).
 *
 * Used by iLife798 3.1.10 login signing; implements full chunk trees per BLAKE3 spec.
 */
object Blake3Keyed {

    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val PARENT = 4
    private const val ROOT = 8
    private const val KEYED_HASH = 16

    private val IV = intArrayOf(
        0x6a09e667.toInt(),
        0xbb67ae85.toInt(),
        0x3c6ef372,
        0xa54ff53a.toInt(),
        0x510e527f,
        0x9b05688c.toInt(),
        0x1f83d9ab,
        0x5be0cd19,
    )

    private val PERMUTATION = intArrayOf(
        2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8,
    )

    fun keyedHash(key: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32) { "BLAKE3 keyed mode requires a 32-byte key (got ${key.size})" }
        val keyWords = words(key, 0, key.size).copyOfRange(0, 8)
        val chunks = if (input.isEmpty()) 1 else (input.size + 1023) / 1024

        fun subtree(first: Int, count: Int): Output {
            if (count == 1) {
                val offset = first * 1024
                val remaining = input.size - offset
                val length = minOf(remaining, 1024)
                val blocks = if (length <= 0) 1 else (length + 63) / 64
                var cv = keyWords
                for (index in 0 until blocks) {
                    val left = length - index * 64
                    val blockLength = left.coerceIn(0, 64)
                    var flags = KEYED_HASH
                    if (index == 0) flags = flags or CHUNK_START
                    if (index == blocks - 1) flags = flags or CHUNK_END
                    val out = Output(
                        cv = cv,
                        block = words(input, offset + index * 64, blockLength),
                        counter = first.toLong(),
                        blockLength = blockLength,
                        flags = flags,
                    )
                    if (index == blocks - 1) return out
                    cv = out.chaining()
                }
                error("BLAKE3 chunk assembly failed")
            }
            val leftCount = highestOneBit(count - 1)
            val leftCv = subtree(first, leftCount).chaining()
            val rightCv = subtree(first + leftCount, count - leftCount).chaining()
            val merged = IntArray(16)
            leftCv.copyInto(merged, destinationOffset = 0)
            rightCv.copyInto(merged, destinationOffset = 8)
            return Output(
                cv = keyWords,
                block = merged,
                counter = 0L,
                blockLength = 64,
                flags = KEYED_HASH or PARENT,
            )
        }

        return subtree(0, chunks).root()
    }

    fun keyedHashHex(key: ByteArray, input: ByteArray): String {
        val digest = keyedHash(key, input)
        return buildString(digest.size * 2) {
            for (b in digest) append("%02x".format(b.toInt() and 0xff))
        }
    }

    private fun highestOneBit(value: Int): Int {
        var result = 1
        while ((result.toLong() shl 1) <= value.toLong()) {
            result = result shl 1
        }
        return result
    }

    private fun words(bytes: ByteArray, offset: Int, length: Int): IntArray {
        val result = IntArray(16)
        val safeLength = length.coerceIn(0, 64)
        for (index in 0 until safeLength) {
            val position = offset + index
            if (position >= bytes.size) break
            result[index / 4] = result[index / 4] or ((bytes[position].toInt() and 0xff) shl (8 * (index % 4)))
        }
        return result
    }

    private fun compress(
        cv: IntArray,
        block: IntArray,
        counter: Long,
        length: Int,
        flags: Int,
    ): IntArray {
        val state = intArrayOf(
            cv[0], cv[1], cv[2], cv[3], cv[4], cv[5], cv[6], cv[7],
            IV[0], IV[1], IV[2], IV[3],
            counter.toInt(), (counter ushr 32).toInt(),
            length, flags,
        )
        var message = block

        fun rotr(value: Int, shift: Int): Int =
            (value ushr shift) or (value shl (32 - shift))

        fun mix(a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
            state[a] = state[a] + state[b] + x
            state[d] = rotr(state[d] xor state[a], 16)
            state[c] = state[c] + state[d]
            state[b] = rotr(state[b] xor state[c], 12)
            state[a] = state[a] + state[b] + y
            state[d] = rotr(state[d] xor state[a], 8)
            state[c] = state[c] + state[d]
            state[b] = rotr(state[b] xor state[c], 7)
        }

        for (round in 0 until 7) {
            mix(0, 4, 8, 12, message[0], message[1])
            mix(1, 5, 9, 13, message[2], message[3])
            mix(2, 6, 10, 14, message[4], message[5])
            mix(3, 7, 11, 15, message[6], message[7])
            mix(0, 5, 10, 15, message[8], message[9])
            mix(1, 6, 11, 12, message[10], message[11])
            mix(2, 7, 8, 13, message[12], message[13])
            mix(3, 4, 9, 14, message[14], message[15])
            if (round < 6) {
                message = IntArray(16) { i -> message[PERMUTATION[i]] }
            }
        }

        return IntArray(16) { idx ->
            if (idx < 8) state[idx] xor state[idx + 8]
            else state[idx] xor cv[idx - 8]
        }
    }

    private class Output(
        private val cv: IntArray,
        private val block: IntArray,
        private val counter: Long,
        private val blockLength: Int,
        private val flags: Int,
    ) {
        fun chaining(): IntArray = compress(cv, block, counter, blockLength, flags).copyOfRange(0, 8)

        fun root(): ByteArray {
            val words = compress(cv, block, 0L, blockLength, flags or ROOT)
            return ByteArray(32) { idx ->
                ((words[idx / 4] ushr (8 * (idx % 4))) and 0xff).toByte()
            }
        }
    }
}
