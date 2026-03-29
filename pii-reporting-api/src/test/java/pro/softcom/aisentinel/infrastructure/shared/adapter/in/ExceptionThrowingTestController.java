package pro.softcom.aisentinel.infrastructure.shared.adapter.in;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller that throws configurable exceptions for GlobalExceptionHandler testing.
 */
@RestController
@RequestMapping("/test")
class ExceptionThrowingTestController {

    static RuntimeException exceptionToThrow;

    @GetMapping("/throw")
    String throwException() {
        if (exceptionToThrow != null) {
            RuntimeException ex = exceptionToThrow;
            exceptionToThrow = null;
            throw ex;
        }
        return "ok";
    }

    @PostMapping("/throw-body")
    String throwWithBody(@RequestBody BodyRequest body) {
        return "ok: " + body.name();
    }

    @PostMapping("/throw-validation")
    String throwValidation(@Valid @RequestBody ValidationRequest body) {
        return "ok: " + body.name();
    }

    record BodyRequest(String name) {}

    record ValidationRequest(@NotBlank String name) {}
}
