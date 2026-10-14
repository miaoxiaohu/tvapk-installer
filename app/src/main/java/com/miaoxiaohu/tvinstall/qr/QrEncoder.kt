package com.miaoxiaohu.tvinstall.qr

/**
 * Minimal ISO/IEC 18004 QR Code encoder.
 *
 * Scope: byte mode, error correction level M, versions 1 through 7, no third-party
 * dependencies. The output is a flat BooleanArray in row-major order where `true`
 * means a dark module.
 */
object QrEncoder {

    const val MIN_VERSION = 1
    const val MAX_VERSION = 7

    /** Byte-mode mode indicator (0100). */
    private const val MODE_BYTE = 0b0100

    /** Character count indicator width for versions 1-9. */
    private const val COUNT_BITS = 8

    /** Error correction level M as used by the format information (00). */
    private const val EC_LEVEL_M = 0b00

    private const val FORMAT_GENERATOR = 0x537
    private const val FORMAT_XOR_MASK = 0x5412
    private const val VERSION_GENERATOR = 0x1F25

    /** Reed-Solomon field polynomial x^8 + x^4 + x^3 + x^2 + 1 and field generator alpha = 2. */
    private const val GF_PRIMITIVE = 0x11D
    private const val GF_GENERATOR = 0x02

    private const val PAYLOAD_TOO_LONG = "Payload too long for QR version 7"

    /** Per-version block parameters for EC level M, index 0 == version 1. */
    private val TOTAL_CODEWORDS = intArrayOf(26, 44, 70, 100, 134, 172, 196)
    private val EC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18)
    private val NUM_BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4)
    private val DATA_PER_BLOCK = intArrayOf(16, 28, 44, 32, 43, 27, 31)

    /** Alignment pattern centre coordinates per version, index 0 == version 1. */
    private val ALIGNMENT_COORDS = arrayOf(
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38)
    )

    // ------------------------------------------------------------------ public API

    /** Encodes [text] as a QR matrix. Each row is one module row; true == dark. */
    @Throws(QrException::class)
    fun encode(text: String, quietZone: Int = 3): BooleanArray {
        if (quietZone < 0) throw QrException("Quiet zone must not be negative: $quietZone")
        val built = build(text)
        val side = built.size + 2 * quietZone
        val out = BooleanArray(side * side)
        for (y in 0 until built.size) {
            System.arraycopy(
                built.modules,
                y * built.size,
                out,
                (y + quietZone) * side + quietZone,
                built.size
            )
        }
        return out
    }

    /** Same but returns raw modules without the quiet zone. */
    @Throws(QrException::class)
    fun encodeNoQuietZone(text: String): BooleanArray = build(text).modules

    class QrException(message: String) : Exception(message)

    // ------------------------------------------------------------------ pipeline

    private class Spec(
        val version: Int,
        val totalCodewords: Int,
        val ecPerBlock: Int,
        val numBlocks: Int,
        val dataPerBlock: Int
    ) {
        val dataCodewords: Int get() = numBlocks * dataPerBlock
        val size: Int get() = 17 + 4 * version
        /** Byte-mode payload capacity: floor((dataCodewords * 8 - 12) / 8). */
        val capacityBytes: Int get() = (dataCodewords * 8 - 12) / 8
    }

    private class Built(val size: Int, val modules: BooleanArray)

    private fun spec(version: Int): Spec {
        val i = version - MIN_VERSION
        if (i < 0 || i > MAX_VERSION - MIN_VERSION) throw QrException("Unsupported QR version: $version")
        return Spec(version, TOTAL_CODEWORDS[i], EC_PER_BLOCK[i], NUM_BLOCKS[i], DATA_PER_BLOCK[i])
    }

    /** Runs the full pipeline: encode -> interleave -> mask -> format/version info. */
    private fun build(text: String): Built {
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Stage 1: pick the smallest version that fits the payload.
        val spec = (MIN_VERSION..MAX_VERSION).map(::spec).firstOrNull { bytes.size <= it.capacityBytes }
            ?: throw QrException(PAYLOAD_TOO_LONG)

        // Stages 2-5: bit stream, terminator, codeword padding.
        val data = assembleDataCodewords(bytes, spec)
        // Stages 6-8: Reed-Solomon, block interleaving, concatenation.
        val codewords = interleave(data, spec)
        // Stages 9-11: function patterns and data placement.
        val canvas = Canvas(spec.size)
        drawFunctionPatterns(canvas, spec)
        placeCodewords(canvas, codewords)
        // Stage 12: mask selection by lowest penalty score.
        val bestMask = selectMask(canvas)
        applyMask(canvas, bestMask)
        // Stages 13-14: format information, then version information for version >= 7.
        drawFormatBits(canvas, spec.size, bestMask)
        if (spec.version >= 7) drawVersionBits(canvas, spec.size, spec.version)
        return Built(spec.size, canvas.modules)
    }

    /** Mode indicator, character count, payload, terminator, then EC/11 padding. */
    private fun assembleDataCodewords(bytes: ByteArray, spec: Spec): IntArray {
        val stream = BitBuffer(bytes.size * 8 + 64)
        stream.append(MODE_BYTE, 4)
        stream.append(bytes.size, COUNT_BITS)
        for (b in bytes) stream.append(b.toInt() and 0xFF, 8)

        val freeBits = spec.dataCodewords * 8 - stream.length
        stream.append(0, if (freeBits < 4) freeBits else 4)

        val out = IntArray(spec.dataCodewords)
        stream.fillCodewords(out)
        return out
    }

    /** Stage 6/7: per-block Reed-Solomon parity. */
    private fun eccForBlock(block: IntArray, ecLength: Int): IntArray {
        val generator = generatorPolynomial(ecLength)
        val remainder = IntArray(ecLength)
        for (value in block) {
            val factor = value xor remainder[0]
            for (i in 0 until ecLength - 1) remainder[i] = remainder[i + 1]
            remainder[ecLength - 1] = 0
            for (i in 0 until ecLength) {
                remainder[i] = remainder[i] xor gfMultiply(generator[i + 1], factor)
            }
        }
        return remainder
    }

    /** Generator polynomial for [degree] parity symbols: product of (x - alpha^i). */
    private fun generatorPolynomial(degree: Int): IntArray {
        if (degree < 1 || degree > 255) throw QrException("Invalid error correction length: $degree")
        var poly = intArrayOf(1)
        var alpha = 1
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMultiply(poly[j], alpha)
            }
            poly = next
            alpha = gfMultiply(alpha, GF_GENERATOR)
        }
        return poly
    }

    private fun gfMultiply(a: Int, b: Int): Int {
        var x = a and 0xFF
        var y = b and 0xFF
        var z = 0
        for (i in 0 until 8) {
            z = z xor (x * (y and 1))
            y = y ushr 1
            x = (x shl 1) xor (if (x and 0x80 != 0) GF_PRIMITIVE else 0)
        }
        return z and 0xFF
    }

    /**
     * Stages 6-8: interleave the data codewords, then interleave the per-block ECC the same
     * way. ISO/IEC 18004 clause 8.6 interleaves both sequences index by index; emitting the
     * ECC block by block instead would make multi-block versions (4-7) undecodable.
     */
    private fun interleave(data: IntArray, spec: Spec): IntArray {
        if (data.size != spec.dataCodewords) throw QrException("Data codeword count mismatch")
        val ecc = IntArray(spec.numBlocks * spec.ecPerBlock)
        for (b in 0 until spec.numBlocks) {
            val block = data.copyOfRange(b * spec.dataPerBlock, (b + 1) * spec.dataPerBlock)
            eccForBlock(block, spec.ecPerBlock).copyInto(ecc, b * spec.ecPerBlock)
        }
        val out = IntArray(spec.totalCodewords)
        var k = 0
        for (i in 0 until spec.dataPerBlock) {
            for (b in 0 until spec.numBlocks) {
                val index = b * spec.dataPerBlock + i
                if (index >= data.size) throw QrException("Data block index out of range")
                out[k++] = data[index]
            }
        }
        for (i in 0 until spec.ecPerBlock) {
            for (b in 0 until spec.numBlocks) {
                if (k >= out.size) throw QrException("Too many codewords")
                out[k++] = ecc[b * spec.ecPerBlock + i]
            }
        }
        if (k != out.size) throw QrException("Codeword count mismatch: $k/${out.size}")
        return out
    }

    // ------------------------------------------------------------------ matrix

    private class Canvas(val size: Int) {
        val modules = BooleanArray(size * size)
        private val function = BooleanArray(size * size)

        fun dark(x: Int, y: Int): Boolean {
            if (x < 0 || y < 0 || x >= size || y >= size) throw QrException("Module out of bounds: $x,$y")
            return modules[y * size + x]
        }

        fun isFunction(x: Int, y: Int): Boolean =
            x >= 0 && y >= 0 && x < size && y < size && function[y * size + x]

        fun setFunction(x: Int, y: Int, dark: Boolean) {
            if (x < 0 || y < 0 || x >= size || y >= size) return
            modules[y * size + x] = dark
            function[y * size + x] = true
        }

        fun setData(x: Int, y: Int, dark: Boolean) {
            if (x < 0 || y < 0 || x >= size || y >= size) throw QrException("Module out of bounds: $x,$y")
            modules[y * size + x] = dark
        }
    }

    private fun drawFunctionPatterns(canvas: Canvas, spec: Spec) {
        val size = canvas.size
        // Timing patterns on row 6 and column 6.
        for (i in 0 until size) {
            canvas.setFunction(6, i, i % 2 == 0)
            canvas.setFunction(i, 6, i % 2 == 0)
        }
        // Finder patterns plus their separators.
        drawFinderPattern(canvas, 3, 3)
        drawFinderPattern(canvas, size - 4, 3)
        drawFinderPattern(canvas, 3, size - 4)
        // Alignment patterns, skipping the three finder pattern corners.
        val coords = ALIGNMENT_COORDS[spec.version - MIN_VERSION]
        for (cy in coords) {
            for (cx in coords) {
                val onFinder = (cx == 6 && cy == 6) ||
                    (cx == 6 && cy == size - 7) ||
                    (cx == size - 7 && cy == 6)
                if (!onFinder) drawAlignmentPattern(canvas, cx, cy)
            }
        }
        // Reserve the format information area; it stays light while masks are scored.
        drawFormatBits(canvas, size, 0, blank = true)
        // Reserve the version information area for version 7 and above.
        if (spec.version >= 7) drawVersionBits(canvas, size, spec.version, blank = true)
    }

    private fun drawFinderPattern(canvas: Canvas, cx: Int, cy: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                canvas.setFunction(cx + dx, cy + dy, dist != 2 && dist != 4)
            }
        }
    }

    private fun drawAlignmentPattern(canvas: Canvas, cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                canvas.setFunction(cx + dx, cy + dy, dist != 1)
            }
        }
    }

    /** Stage 11: standard two-column zigzag placement, right edge to left edge. */
    private fun placeCodewords(canvas: Canvas, codewords: IntArray) {
        var bitIndex = 0
        val totalBits = codewords.size * 8
        var right = canvas.size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until canvas.size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) canvas.size - 1 - vert else vert
                    if (!canvas.isFunction(x, y) && bitIndex < totalBits) {
                        val bit = (codewords[bitIndex ushr 3] ushr (7 - (bitIndex and 7))) and 1
                        canvas.setData(x, y, bit == 1)
                        bitIndex++
                    }
                }
            }
            right -= 2
        }
        if (bitIndex != totalBits) throw QrException("Placed $bitIndex of $totalBits data bits")
    }

    // ------------------------------------------------------------------ masking

    private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
        else -> throw QrException("Invalid mask pattern: $mask")
    }

    private fun applyMask(canvas: Canvas, mask: Int) {
        for (y in 0 until canvas.size) {
            for (x in 0 until canvas.size) {
                if (!canvas.isFunction(x, y) && maskBit(mask, x, y)) {
                    canvas.setData(x, y, !canvas.dark(x, y))
                }
            }
        }
    }

    /** Stage 12: try all eight masks and keep the lowest penalty (first one on ties). */
    private fun selectMask(canvas: Canvas): Int {
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        for (mask in 0..7) {
            val trial = canvas.modules.copyOf()
            for (y in 0 until canvas.size) {
                for (x in 0 until canvas.size) {
                    if (!canvas.isFunction(x, y) && maskBit(mask, x, y)) {
                        trial[y * canvas.size + x] = !trial[y * canvas.size + x]
                    }
                }
            }
            val score = penalty(trial, canvas.size)
            if (score < bestScore) {
                bestScore = score
                bestMask = mask
            }
        }
        return bestMask
    }

    /** N1 + N2 + N3 + N4 penalty score of a module grid. */
    private fun penalty(modules: BooleanArray, size: Int): Int {
        var score = penaltyN1(modules, size) + penaltyN2(modules, size) + penaltyN4(modules, size)
        val row = BooleanArray(size)
        for (y in 0 until size) {
            for (x in 0 until size) row[x] = modules[y * size + x]
            score += penaltyN3(row, 0, 1, size)
        }
        val col = BooleanArray(size)
        for (x in 0 until size) {
            for (y in 0 until size) col[y] = modules[y * size + x]
            score += penaltyN3(col, 0, 1, size)
        }
        return score
    }

    /** N1: three points plus one extra per module for runs of five or more. */
    private fun penaltyN1(modules: BooleanArray, size: Int): Int {
        var score = 0
        val line = BooleanArray(size)
        for (y in 0 until size) {
            for (x in 0 until size) line[x] = modules[y * size + x]
            score += runPenalty(line)
        }
        for (x in 0 until size) {
            for (y in 0 until size) line[y] = modules[y * size + x]
            score += runPenalty(line)
        }
        return score
    }

    private fun runPenalty(line: BooleanArray): Int {
        var score = 0
        var run = 1
        for (i in 1 until line.size) {
            if (line[i] == line[i - 1]) {
                run++
            } else {
                if (run >= 5) score += 3 + (run - 5)
                run = 1
            }
        }
        if (run >= 5) score += 3 + (run - 5)
        return score
    }

    /** N2: three points for every 2x2 block of one colour. */
    private fun penaltyN2(modules: BooleanArray, size: Int): Int {
        var score = 0
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val v = modules[y * size + x]
                if (v == modules[y * size + x + 1] &&
                    v == modules[(y + 1) * size + x] &&
                    v == modules[(y + 1) * size + x + 1]
                ) {
                    score += 3
                }
            }
        }
        return score
    }

    /** N3: forty points per 1:1:3:1:1 finder-like pattern with four light modules beside it. */
    private fun penaltyN3(line: BooleanArray, offset: Int, stride: Int, length: Int): Int {
        fun dark(i: Int): Boolean = line[offset + i * stride]
        var score = 0
        for (start in 0..length - 11) {
            val common = !dark(start + 1) && dark(start + 4) && !dark(start + 5) &&
                dark(start + 6) && !dark(start + 9)
            if (!common) continue
            val leadingLight = !dark(start) && !dark(start + 2) && !dark(start + 3) &&
                dark(start + 7) && dark(start + 8) && dark(start + 10)
            val trailingLight = dark(start) && dark(start + 2) && dark(start + 3) &&
                !dark(start + 7) && !dark(start + 8) && !dark(start + 10)
            if (leadingLight || trailingLight) score += 40
        }
        return score
    }

    /** N4: ten points per five percent the dark ratio deviates from fifty percent. */
    private fun penaltyN4(modules: BooleanArray, size: Int): Int {
        var darkCount = 0
        for (m in modules) if (m) darkCount++
        val percent = darkCount.toDouble() / (size.toDouble() * size.toDouble())
        val deviation = kotlin.math.abs(percent * 100.0 - 50.0) / 5.0
        return deviation.toInt() * 10
    }

    // ------------------------------------------------------------------ info areas

    private fun bitAt(value: Int, index: Int): Boolean = ((value ushr index) and 1) == 1

    /**
     * Stage 13: 5 format bits, BCH(15,5), masked with 0x5412, written in both copies.
     * [blank] reserves the area as light modules, which is how it looks while masks are scored.
     */
    private fun drawFormatBits(canvas: Canvas, size: Int, mask: Int, blank: Boolean = false) {
        val data = (EC_LEVEL_M shl 3) or (mask and 0x07)
        var rem = data
        for (i in 0 until 10) {
            rem = (rem shl 1) xor ((rem ushr 9) * FORMAT_GENERATOR)
        }
        val bits = ((data shl 10) or rem) xor FORMAT_XOR_MASK
        fun put(bitIndex: Int, x: Int, y: Int) =
            canvas.setFunction(x, y, if (blank) false else bitAt(bits, bitIndex))

        for (i in 0..5) put(i, 8, i)
        put(6, 8, 7)
        put(7, 8, 8)
        put(8, 7, 8)
        for (i in 9..14) put(i, 14 - i, 8)

        for (i in 0..7) put(i, size - 1 - i, 8)
        for (i in 8..14) put(i, 8, size - 15 + i)

        canvas.setFunction(8, size - 8, !blank)
    }

    /** Stage 14: 6 version bits plus BCH(18,6), written top-right and bottom-left. */
    private fun drawVersionBits(canvas: Canvas, size: Int, version: Int, blank: Boolean = false) {
        var rem = version
        for (i in 0 until 12) {
            rem = (rem shl 1) xor ((rem ushr 11) * VERSION_GENERATOR)
        }
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val bit = if (blank) false else bitAt(bits, i)
            val a = size - 11 + i % 3
            val b = i / 3
            canvas.setFunction(a, b, bit)
            canvas.setFunction(b, a, bit)
        }
    }

    // ------------------------------------------------------------------ bit buffer

    private class BitBuffer(initialCapacity: Int) {
        private var data = BooleanArray(if (initialCapacity < 16) 16 else initialCapacity)
        var length = 0
            private set

        fun append(value: Int, count: Int) {
            if (count < 0 || count > 31) throw QrException("Invalid bit count: $count")
            if (count < 32 && value.ushr(count) != 0) throw QrException("Value $value exceeds $count bits")
            if (length + count > data.size) data = data.copyOf((data.size * 2).coerceAtLeast(length + count))
            for (i in count - 1 downTo 0) data[length++] = ((value ushr i) and 1) == 1
        }

        /** Copies the stream into [target] codewords, zero padding and then 0xEC / 0x11. */
        fun fillCodewords(target: IntArray) {
            if (target.size * 8 < length) throw QrException("Bit stream does not fit in codewords")
            var index = 0
            var acc = 0
            var bitsInAcc = 0
            var i = 0
            while (i < length) {
                acc = (acc shl 1) or (if (data[i]) 1 else 0)
                bitsInAcc++
                i++
                if (bitsInAcc == 8) {
                    target[index++] = acc
                    acc = 0
                    bitsInAcc = 0
                }
            }
            if (bitsInAcc > 0) target[index++] = acc shl (8 - bitsInAcc)
            var padIndex = 0
            while (index < target.size) {
                val pad = if ((padIndex and 1) == 0) 0xEC else 0x11
                target[index++] = pad
                padIndex++
            }
        }
    }
}
