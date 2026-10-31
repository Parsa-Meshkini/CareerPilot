package com.careerpilot.server;

import com.careerpilot.common.DomainException;
import com.careerpilot.users.AuthService;
import com.careerpilot.users.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Resolves "Authorization: Bearer <token>" to the signed-in user for every /api request except sign-in itself. */
@Component
public class TokenAuthInterceptor implements HandlerInterceptor {
    public static final String USER_ATTRIBUTE = "careerpilot.user";
    private final AuthService auth;

    public TokenAuthInterceptor(AuthService auth) { this.auth = auth; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod())) return true;
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) throw new DomainException("unauthenticated", 401, "Sign in first.");
        User user = auth.authenticate(header.substring(7).strip());
        request.setAttribute(USER_ATTRIBUTE, user);
        return true;
    }
}
