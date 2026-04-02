package com.anthropic.claudecode.native

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan

/**
 * Tests for NativeSupport / NativeSupport 测试
 */
class NativeSupportTest : DescribeSpec({

    describe("NativeSupport") {
        it("should detect non-native environment in tests") {
            // Tests run on JVM, not native image
            NativeSupport.isNativeImage shouldBe false
        }

        it("should return build info") {
            val info = NativeSupport.getBuildInfo()
            info.version shouldNotBe null
            info.isNative shouldBe false
            info.platform.isNotBlank() shouldBe true
        }

        it("should produce display string") {
            val info = NativeSupport.getBuildInfo()
            val display = info.toDisplayString()
            display shouldContain "Claude Code Kotlin"
            display shouldContain "JVM"
        }

        it("should return temp dir") {
            val dir = NativeSupport.getTempDir()
            dir.isNotBlank() shouldBe true
        }

        it("should list reflection classes") {
            val classes = NativeSupport.getReflectionClasses()
            classes.size shouldBeGreaterThan 5
            classes.any { it.contains("Message") } shouldBe true
        }

        it("should list resource patterns") {
            val patterns = NativeSupport.getResourcePatterns()
            patterns.size shouldBeGreaterThan 0
        }
    }
})
