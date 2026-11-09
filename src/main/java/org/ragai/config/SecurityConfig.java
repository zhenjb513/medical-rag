package org.ragai.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
@ConditionalOnProperty(name = "app.security.form-login-enabled", havingValue = "true", matchIfMissing = true)
public class SecurityConfig {

    private static final List<String> API_PREFIXES = List.of(
            "/chat", "/evaluation", "/bm25", "/rag", "/auth", "/ingest"
    );

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/", "/index.html",
                                "/login",
                                "/auth/**",
                                "/error",
                                "/static/**", "/css/**", "/js/**",
                                // 前端是零构建的裸文件，index.html 里写的是 src="app.js"，
                                // 落到根路径上而不是 /static/ 或 /js/ 下；漏了这两条就是 401，
                                // 页面变成没样式的裸 HTML 且所有按钮失效。
                                "/app.js", "/style.css",
                                "/favicon.ico", "/actuator/**",
                                "/swagger-ui/**", "/swagger-resources/**",
                                "/v3/api-docs/**", "/webjars/**"
                        ).permitAll()
                        .requestMatchers("/chat/**", "/evaluation/**", "/bm25/**", "/rag/**", "/ingest/**").permitAll()
                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(
                                "/chat/**", "/evaluation/**", "/bm25/**", "/rag/**", "/ingest/**",
                                "/auth/**", "/login", "/logout"
                        )
                )
                .formLogin(form -> form
                        // 不指定 loginPage：仓库里从来没有 static/login.html，指过去浏览器就是
                        // 500「No static resource login.html」。用 Spring Security 自带的 GET /login 页兜底，
                        // 前端正常登录走 index.html 上的模态框直接 POST /login。
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .usernameParameter("username")
                        .passwordParameter("password")
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                )
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                new ApiRequestMatcher()
                        )
                );

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(SecurityProperties props) {
        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
        List<SecurityProperties.UserDef> users = props.users();
        if (users == null || users.isEmpty()) {
            users = List.of(
                    new SecurityProperties.UserDef("admin", "admin123", List.of("ADMIN")),
                    new SecurityProperties.UserDef("doctor", "doctor123", List.of("USER"))
            );
        }
        for (SecurityProperties.UserDef u : users) {
            List<String> roles = u.roles() != null ? u.roles() : List.of("USER");
            UserDetails ud = User.builder()
                    .username(u.username())
                    .password("{noop}" + u.password())
                    .roles(roles.toArray(new String[0]))
                    .build();
            manager.createUser(ud);
        }
        return manager;
    }

    static class ApiRequestMatcher implements RequestMatcher {
        @Override
        public boolean matches(HttpServletRequest request) {
            String path = request.getRequestURI();
            for (String prefix : API_PREFIXES) {
                if (path.startsWith(prefix)) return true;
            }
            return false;
        }
    }
}