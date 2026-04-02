package com.anthropic.claudecode.types

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests for ValidationResult and PermissionResult
 * ValidationResult 和 PermissionResult 测试
 */
class ValidationPermissionTest : DescribeSpec({

    describe("ValidationResult") {
        it("should create Success") {
            val result: ValidationResult = ValidationResult.Success
            result.shouldBeInstanceOf<ValidationResult.Success>()
        }

        it("should create Failure with details") {
            val result: ValidationResult = ValidationResult.Failure(
                message = "File path is required",
                errorCode = 400
            )
            result.shouldBeInstanceOf<ValidationResult.Failure>()
            (result as ValidationResult.Failure).message shouldBe "File path is required"
            result.errorCode shouldBe 400
        }
    }

    describe("PermissionResult") {
        it("should create Allow") {
            val result: PermissionResult = PermissionResult.Allow()
            result.shouldBeInstanceOf<PermissionResult.Allow>()
        }

        it("should create Allow with updated input") {
            val result = PermissionResult.Allow(
                userModified = true,
                acceptFeedback = "Looks good"
            )
            result.userModified shouldBe true
            result.acceptFeedback shouldBe "Looks good"
        }

        it("should create Deny with reason") {
            val result = PermissionResult.Deny(
                message = "Not allowed",
                decisionReason = PermissionDecisionReason.Mode(PermissionMode.PLAN)
            )
            result.message shouldBe "Not allowed"
            result.decisionReason.shouldBeInstanceOf<PermissionDecisionReason.Mode>()
        }

        it("should create Ask with suggestions") {
            val result = PermissionResult.Ask(
                message = "Allow file write?"
            )
            result.message shouldBe "Allow file write?"
            result.suggestions shouldBe emptyList()
        }
    }

    describe("PermissionMode") {
        it("should have all expected modes") {
            val modes = PermissionMode.entries.map { it.name }
            modes.contains("DEFAULT") shouldBe true
            modes.contains("ACCEPT_EDITS") shouldBe true
            modes.contains("BYPASS_PERMISSIONS") shouldBe true
            modes.contains("PLAN") shouldBe true
            modes.contains("AUTO") shouldBe true
        }
    }

    describe("ToolResult") {
        it("should create success result") {
            val result = ToolResult(data = "file contents here")
            result.isError shouldBe false
            result.data shouldBe "file contents here"
        }

        it("should create error result") {
            val result = ToolResult<String>(
                isError = true,
                errorMessage = "File not found"
            )
            result.isError shouldBe true
            result.errorMessage shouldBe "File not found"
        }
    }
})
