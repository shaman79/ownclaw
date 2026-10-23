package com.ownclaw.interfaces.web;

import com.ownclaw.privacy.VaultSecrets;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Everything the ops and debug APIs answer, scrubbed of every user's secret vault values on the
 * way out ({@link VaultSecrets#scrubAllDeep}): a text, and the texts in its maps and lists.
 * <p>
 * Those APIs read back what was written elsewhere -- log lines of seven days, long-running task
 * rows, usage rows, chats, events, any table a query names -- and the ops token is held by
 * cloud model sessions. Each door now scrubs what it writes, but what was written before it did
 * is still there as written; scrubbed here, it does not leave by these two.
 */
@ControllerAdvice(assignableTypes = {OpsController.class, DebugController.class})
public class ScrubbedTraces implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        return VaultSecrets.scrubAllDeep(body);
    }
}
