package net.ximatai.muyun.spring.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Makes standard tree-query values available to server-side tree scope policies.
 *
 * <p>Tree traversal remains server-side.  This context only transports the descriptor-owned
 * external values from the POST tree query into the existing request-based scope hooks; it does
 * not authorize or filter records in the browser. A tree scope must preserve the visible
 * ancestors of every visible node so server traversal can retain a valid tree.</p>
 */
public final class TreeWebQuerySupport {
    private static final String QUERY_REQUEST_ATTRIBUTE = TreeWebQuerySupport.class.getName() + ".queryRequest";
    private static final String QUERY_REQUEST_BOUND_ATTRIBUTE = TreeWebQuerySupport.class.getName() + ".queryBound";

    private TreeWebQuerySupport() {
    }

    public static void bind(HttpServletRequest request, WebQueryRequest queryRequest) {
        if (request != null) {
            request.setAttribute(QUERY_REQUEST_ATTRIBUTE, queryRequest);
            request.setAttribute(QUERY_REQUEST_BOUND_ATTRIBUTE, Boolean.TRUE);
        }
    }

    public static Object externalQueryValue(HttpServletRequest request, String key) {
        if (request == null || key == null || key.isBlank()) {
            return null;
        }
        if (Boolean.TRUE.equals(request.getAttribute(QUERY_REQUEST_BOUND_ATTRIBUTE))) {
            Object queryRequest = request.getAttribute(QUERY_REQUEST_ATTRIBUTE);
            return queryRequest instanceof WebQueryRequest query ? query.externalQueryValues().get(key) : null;
        }
        // GET tree and subtree endpoints retain their request-parameter scope contract.
        return request.getParameter(key);
    }

    public static String externalQueryText(HttpServletRequest request, String key) {
        Object value = externalQueryValue(request, key);
        return value == null ? null : String.valueOf(value);
    }
}
