package com.rronin.financialagent.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelFailureTest {
    @Test void quotaAndAuthenticationAreNotTransientRateLimits() {
        assertThat(ModelFailure.http(429, "insufficient_quota").retryable()).isFalse();
        assertThat(ModelFailure.http(401, "invalid_api_key").kind()).isEqualTo(ModelFailure.Kind.AUTHENTICATION);
        assertThat(ModelFailure.http(429, "rate_limit_exceeded").retryable()).isTrue();
        assertThat(ModelFailure.http(503, "server_error").retryable()).isTrue();
        assertThat(ModelFailure.http(400, "context_length_exceeded").kind()).isEqualTo(ModelFailure.Kind.CONTEXT_OVERFLOW);
        assertThat(ModelFailure.http(400, "invalid_request_error").retryable()).isFalse();
    }
    @Test void sensitiveUpstreamTextIsNotIncludedInPublicException() {
        assertThat(ModelFailure.http(401, "secret supplied by remote server").getMessage()).doesNotContain("secret supplied");
    }
}
