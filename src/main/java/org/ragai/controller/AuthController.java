package org.ragai.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @GetMapping("/status")
    public Map<String, Object> status() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean loggedIn = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
        Map<String, Object> result = new HashMap<>();
        result.put("loggedIn", loggedIn);
        if (loggedIn) {
            result.put("username", auth.getName());
            result.put("roles", auth.getAuthorities().stream()
                    .map(g -> g.getAuthority()).toList());
        }
        return result;
    }
}