package net.ximatai.muyun.spring.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.LanguageContext;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** The effective request language is carried across Servlet redispatches and always released. */
public final class LanguageWebFilter extends OncePerRequestFilter implements Ordered {
    private static final String ATTRIBUTE = LanguageWebFilter.class.getName() + ".language";
    private final LanguageCatalog catalog;
    public LanguageWebFilter(LanguageCatalog catalog) { this.catalog = catalog; }
    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override
    protected boolean shouldNotFilterErrorDispatch() { return false; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tag = request.getAttribute(ATTRIBUTE) instanceof String carried ? carried : catalog.select(request.getHeader("Accept-Language"));
        request.setAttribute(ATTRIBUTE, tag);
        response.setHeader("Content-Language", tag);
        response.addHeader("Vary", "Accept-Language");
        try (LanguageContext.Scope ignored = LanguageContext.use(tag)) { chain.doFilter(request, response); }
    }
    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }
}
