package com.anthropic.claudecode.services.api

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.runBlocking

/**
 * Tests for RetryPolicy / RetryPolicy 测试
 */
class RetryPolicyTest : DescribeSpec({

    describe("RetryPolicy") {
        it("should succeed on first try") {
            val policy = RetryPolicy(maxRetries = 3)
            var callCount = 0

            val result = runBlocking {
                policy.execute {
                    callCount++
                    "success"
                }
            }

            result shouldBe "success"
            callCount shouldBe 1
        }

        it("should retry on retryable status codes") {
            val policy = RetryPolicy(maxRetries = 3, baseDelayMs = 10)
            var callCount = 0

            val result = runBlocking {
                policy.execute {
                    callCount++
                    if (callCount < 3) throw ApiException("Rate limited", 429)
                    "success"
                }
            }

            result shouldBe "success"
            callCount shouldBe 3
        }

        it("should not retry on non-retryable status") {
            val policy = RetryPolicy(maxRetries = 3, baseDelayMs = 10)
            var callCount = 0

            shouldThrow<ApiException> {
                runBlocking {
                    policy.execute {
                        callCount++
                        throw ApiException("Not found", 404)
                    }
                }
            }

            callCount shouldBe 1
        }

        it("should exhaust retries") {
            val policy = RetryPolicy(maxRetries = 2, baseDelayMs = 10)
            var callCount = 0

            shouldThrow<ApiException> {
                runBlocking {
                    policy.execute {
                        callCount++
                        throw ApiException("Server error", 500)
                    }
                }
            }

            callCount shouldBe 3 // initial + 2 retries
        }

        it("should handle retry-after header") {
            val policy = RetryPolicy(maxRetries = 1, baseDelayMs = 10)
            var callCount = 0

            val result = runBlocking {
                policy.execute {
                    callCount++
                    if (callCount == 1) throw ApiException("Rate limited", 429, retryAfterMs = 50)
                    "success"
                }
            }

            result shouldBe "success"
            callCount shouldBe 2
        }
    }

    describe("ApiException") {
        it("should format message with status code") {
            val ex = ApiException("Not found", 404)
            ex.message shouldBe "HTTP 404: Not found"
            ex.statusCode shouldBe 404
        }
    }
})
