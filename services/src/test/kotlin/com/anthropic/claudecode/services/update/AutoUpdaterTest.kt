package com.anthropic.claudecode.services.update

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for AutoUpdater
 * AutoUpdater 测试
 */
class AutoUpdaterTest : DescribeSpec({

    describe("AutoUpdater.isNewer") {
        val updater = AutoUpdater(currentVersion = "0.1.0")

        it("should detect newer major version") {
            updater.isNewer("1.0.0", "0.1.0") shouldBe true
        }

        it("should detect newer minor version") {
            updater.isNewer("0.2.0", "0.1.0") shouldBe true
        }

        it("should detect newer patch version") {
            updater.isNewer("0.1.1", "0.1.0") shouldBe true
        }

        it("should not flag same version as newer") {
            updater.isNewer("0.1.0", "0.1.0") shouldBe false
        }

        it("should not flag older version as newer") {
            updater.isNewer("0.0.9", "0.1.0") shouldBe false
        }

        it("should handle v prefix") {
            updater.isNewer("v1.0.0", "v0.1.0") shouldBe true
        }

        it("should handle different length versions") {
            updater.isNewer("1.0.0.1", "1.0.0") shouldBe true
        }
    }

    describe("AutoUpdater.checkForUpdates") {
        it("should return current version info") {
            val updater = AutoUpdater(
                currentVersion = "0.1.0",
                cacheDir = kotlin.io.path.createTempDirectory("updater-test-").toString()
            )
            val result = updater.checkForUpdates()
            result.currentVersion shouldBe "0.1.0"
        }
    }
})
