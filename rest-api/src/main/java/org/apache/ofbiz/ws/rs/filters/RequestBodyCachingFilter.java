/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ofbiz.ws.rs.filters;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

/**
 * Caches JSON request bodies at the servlet boundary so downstream OFBiz and
 * Jersey code can safely read the request body more than once.
 */
public class RequestBodyCachingFilter implements Filter {

    public static final String CACHED_BODY_ATTRIBUTE =
            RequestBodyCachingFilter.class.getName() + ".cachedBody";

    private static final int MAX_JSON_BODY_BYTES = 5 * 1024 * 1024;

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest)
                || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!shouldCache(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        byte[] body = readBody(httpRequest, httpResponse);
        if (body == null) {
            return;
        }

        CachedBodyRequest wrappedRequest = new CachedBodyRequest(httpRequest, body);
        wrappedRequest.setAttribute(CACHED_BODY_ATTRIBUTE, body);

        chain.doFilter(wrappedRequest, response);
    }

    private boolean shouldCache(HttpServletRequest request) {
        String method = request.getMethod();
        if (!("POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method))) {
            return false;
        }

        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }

        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("application/json")
                || normalized.contains("+json");
    }

    private byte[] readBody(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_JSON_BODY_BYTES) {
            response.sendError(
                    413,
                    "JSON request body exceeds the REST API limit of "
                            + MAX_JSON_BODY_BYTES + " bytes");
            return null;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                declaredLength > 0 && declaredLength < Integer.MAX_VALUE
                        ? (int) declaredLength
                        : 1024);

        byte[] buffer = new byte[8192];
        int total = 0;
        int read;

        ServletInputStream original = request.getInputStream();
        while ((read = original.read(buffer)) != -1) {
            total += read;
            if (total > MAX_JSON_BODY_BYTES) {
                response.sendError(
                        413,
                        "JSON request body exceeds the REST API limit of "
                                + MAX_JSON_BODY_BYTES + " bytes");
                return null;
            }
            out.write(buffer, 0, read);
        }

        return out.toByteArray();
    }

    @Override
    public void destroy() {
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body != null ? body : new byte[0];
        }

        @Override
        public ServletInputStream getInputStream() {
            final ByteArrayInputStream input = new ByteArrayInputStream(body);

            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return input.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // Synchronous REST processing only.
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null
                    ? Charset.forName(encoding)
                    : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
