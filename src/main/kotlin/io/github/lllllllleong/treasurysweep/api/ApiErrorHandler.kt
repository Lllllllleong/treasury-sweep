package io.github.lllllllleong.treasurysweep.api

import io.github.lllllllleong.treasurysweep.customer.CustomerNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Thrown while turning a request into domain values: the client sent something we won't accept.
 * Kept separate from IllegalArgumentException, which signals a bug in our own code and should stay a 500.
 */
class InvalidRequestException(message: String) : RuntimeException(message)

/**
 * Maps exceptions to RFC 9457 problem responses (application/problem+json).
 * Extending ResponseEntityExceptionHandler does the same for Spring MVC's own errors:
 * malformed JSON, a failed @Valid, a non-numeric path id, an unsupported method.
 */
@RestControllerAdvice
class ApiErrorHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler
    fun invalidRequest(e: InvalidRequestException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid_request", e.message)

    @ExceptionHandler
    fun customerNotFound(e: CustomerNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "customer_not_found", e.message)
}

/** A problem response with a stable, machine-readable `code` next to the standard fields. */
fun problem(status: HttpStatus, code: String, detail: String?): ProblemDetail =
    ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code) }
