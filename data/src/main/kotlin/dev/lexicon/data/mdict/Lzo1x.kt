/*
 * Adapted from Airlift aircompressor LzoRawDecompressor (version 0.27),
 * Copyright Airlift contributors. Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 * This adaptation uses bounded byte arrays instead of JVM Unsafe for Android.
 */
package dev.lexicon.data.mdict

/** Raw LZO1X blocks used by MDX 1.x. No native code or unsafe buffer access. */
internal object Lzo1x {
    fun decode(input: ByteArray, expectedSize: Int): ByteArray {
        require(expectedSize in 0..MdxReader.MAX_BLOCK_SIZE) { "MDX 解压块过大" }
        val output = ByteArray(expectedSize)
        var ip = 0; var op = 0
        fun byte(): Int { require(ip < input.size) { "LZO 数据截断" }; return input[ip++].toInt() and 255 }
        fun short(): Int = byte() or (byte() shl 8)
        fun length(initial: Int, mask: Int): Int {
            if (initial != 0) return initial
            var count = mask
            var b = byte()
            while (b == 0) { count += 255; require(count <= expectedSize) { "LZO 长度越界" }; b = byte() }
            return count + b
        }
        while (ip < input.size) {
            var first = true; var lastLiterals = 0; var terminated = false
            while (!terminated) {
                val command = byte()
                var matchLength = 0; var matchOffset = 0; val literals: Int
                when {
                    command and 0xf0 == 0 -> when {
                        lastLiterals == 0 -> literals = length(command and 15, 15) + 3
                        lastLiterals <= 3 -> {
                            matchLength = 2; matchOffset = ((command and 12) ushr 2) or (byte() shl 2)
                            literals = command and 3
                        }
                        else -> {
                            matchLength = 3; matchOffset = ((command and 12) ushr 2) or (byte() shl 2) or 2048
                            literals = command and 3
                        }
                    }
                    first -> literals = command - 17
                    command and 0xf0 == 0x10 -> {
                        matchLength = length(command and 7, 7) + 2
                        val trailer = short()
                        matchOffset = ((command and 8) shl 11) + (trailer ushr 2)
                        if (matchOffset == 0) { terminated = true; continue }
                        matchOffset += 16383; literals = trailer and 3
                    }
                    command and 0xe0 == 0x20 -> {
                        matchLength = length(command and 31, 31) + 2
                        val trailer = short(); matchOffset = trailer ushr 2; literals = trailer and 3
                    }
                    command and 0xc0 != 0 -> {
                        matchLength = ((command and 0xe0) ushr 5) + 1
                        matchOffset = ((command and 0x1c) ushr 2) or (byte() shl 3); literals = command and 3
                    }
                    else -> throw IllegalArgumentException("LZO 指令无效")
                }
                first = false
                if (matchLength != 0) {
                    matchOffset++
                    require(matchLength >= 0 && matchOffset <= op && matchLength <= output.size - op) { "LZO 匹配越界" }
                    repeat(matchLength) { output[op] = output[op - matchOffset]; op++ }
                }
                require(literals >= 0 && literals <= input.size - ip && literals <= output.size - op) { "LZO 字面数据越界" }
                input.copyInto(output, op, ip, ip + literals); ip += literals; op += literals
                lastLiterals = literals
            }
        }
        require(op == expectedSize) { "LZO 解压大小不一致" }
        return output
    }
}
