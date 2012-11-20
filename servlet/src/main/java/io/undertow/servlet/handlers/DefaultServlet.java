/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.servlet.handlers;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.undertow.server.HttpCompletionHandler;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.blocking.BlockingHttpServerExchange;
import io.undertow.server.handlers.file.DirectFileCache;
import io.undertow.server.handlers.file.FileCache;
import io.undertow.servlet.api.DefaultServletConfig;
import io.undertow.servlet.api.Deployment;
import io.undertow.servlet.spec.HttpServletRequestImpl;
import org.xnio.IoUtils;

/**
 * Default servlet responsible for serving up resources. This is both a handler and a servlet. If no filters
 * match the current path then the resources will be served up asynchronously using the
 * {@link #handleRequest(io.undertow.server.HttpServerExchange, io.undertow.server.HttpCompletionHandler)} method,
 * otherwise the request is handled as a normal servlet request.
 * <p/>
 * By default we only allow a restricted set of extensions.
 *
 * @author Stuart Douglas
 */
public class DefaultServlet extends HttpServlet implements HttpHandler {


    private final Deployment deployment;
    private volatile FileCache fileCache = DirectFileCache.INSTANCE;
    private final DefaultServletConfig config;

    private final List<String> welcomePages;

    public DefaultServlet(final Deployment deployment, final DefaultServletConfig config, final List<String> welcomePages) {
        this.deployment = deployment;
        this.config = config;
        this.welcomePages = welcomePages;
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        final String path = getPath(req);
        if (!isAllowed(path)) {
            resp.setStatus(404);
            return;
        }
        final File resource = deployment.getDeploymentInfo().getResourceLoader().getResource(path);
        if (resource == null) {
            if (req.getDispatcherType() == DispatcherType.INCLUDE) {
                //servlet 9.3
                throw new FileNotFoundException(path);
            } else {
                resp.setStatus(404);
            }
            return;
        } else if (resource.isDirectory()) {
            handleWelcomePage(req, resp, resource);
        } else {
            serveFileBlocking(resp, resource);
        }
    }

    private void serveFileBlocking(final HttpServletResponse resp, final File resource) throws IOException {
        ServletOutputStream out = null;
        InputStream in = new BufferedInputStream(new FileInputStream(resource));
        try {
            int read;
            final byte[] buffer = new byte[1024];
            out = resp.getOutputStream();
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            if (out != null) {
                IoUtils.safeClose(out);
            }
            IoUtils.safeClose(in);
        }
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange, final HttpCompletionHandler completionHandler) {
        if (!isAllowed(exchange.getRelativePath())) {
            //we don't call the completion handler, as we allow the initial handler to do error handling
            exchange.setResponseCode(404);
            return;
        }
        File resource = deployment.getDeploymentInfo().getResourceLoader().getResource(exchange.getRelativePath());
        if (resource == null) {
            exchange.setResponseCode(404);
            return;
        } else if (resource.isDirectory()) {
            handleWelcomePage(exchange, completionHandler, resource);
        } else {
            fileCache.serveFile(exchange, completionHandler, resource, false);
        }
    }

    private void handleWelcomePage(final HttpServerExchange exchange, final HttpCompletionHandler completionHandler, final File resource) {
        File welcomePage = findWelcomeFile(resource);
        if (welcomePage != null) {
            fileCache.serveFile(exchange, completionHandler, welcomePage, false);
        } else {
            ServletPathMatch handler = findWelcomeServlet(exchange.getRelativePath().endsWith("/") ? exchange.getRelativePath() : exchange.getRelativePath() + "/");
            if (handler != null && handler.getHandler() != null) {
                exchange.setRequestPath(exchange.getResolvedPath() + handler.getMatched());
                exchange.setRequestURI(exchange.getResolvedPath() + handler.getMatched());
                exchange.putAttachment(ServletPathMatch.ATTACHMENT_KEY, handler);
                handler.getHandler().handleRequest(exchange, completionHandler);
            } else {
                exchange.setResponseCode(404);
                completionHandler.handleComplete();
            }
        }
    }

    private void handleWelcomePage(final HttpServletRequest req, final HttpServletResponse resp, final File resource) throws IOException, ServletException {
        File welcomePage = findWelcomeFile(resource);
        if (welcomePage != null) {
            serveFileBlocking(resp, welcomePage);
        } else {
            ServletPathMatch handler = findWelcomeServlet(req.getPathInfo().endsWith("/") ? req.getPathInfo() : req.getPathInfo() + "/");
            if (handler != null) {
                HttpServletRequestImpl servletRequestImpl = HttpServletRequestImpl.getRequestImpl(req);
                BlockingHttpServerExchange exchange = servletRequestImpl.getExchange();
                exchange.getExchange().setRequestPath(exchange.getExchange().getResolvedPath() + handler.getMatched());
                exchange.getExchange().setRequestURI(exchange.getExchange().getResolvedPath() + handler.getMatched());
                exchange.getExchange().putAttachment(ServletPathMatch.ATTACHMENT_KEY, handler);
                try {
                    handler.getHandler().handleRequest(exchange);
                } catch (ServletException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ServletException(e);
                }
            } else {
                resp.setStatus(404);
            }
        }
    }

    private File findWelcomeFile(final File resource) {
        for (String i : welcomePages) {
            final File res = new File(resource + File.separator + i);
            if (res.exists()) {
                return res;
            }
        }
        return null;
    }

    private ServletPathMatch findWelcomeServlet(final String path) {
        for (String i : welcomePages) {
            final ServletPathMatch handler = deployment.getServletPaths().getServletHandlerByPath(path + i);
            if (handler.getHandler().getManagedServlet() != null && handler.getHandler().getManagedServlet().getServletInfo().getServletClass() != DefaultServlet.class) {
                return handler;
            }
        }
        return null;
    }

    private String getPath(final HttpServletRequest request) {
        if (request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null) {
            String result = (String) request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
            if (result == null) {
                result = (String) request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
            }
            if (result == null || result.equals("")) {
                result = "/";
            }
            return result;
        }
        String result = request.getPathInfo();
        if (result == null) {
            result = request.getServletPath();
        }
        if ((result == null) || (result.equals(""))) {
            result = "/";
        }
        return result;
    }

    private boolean isAllowed(String path) {
        int pos = path.lastIndexOf('/');
        final String lastSegment;
        if (pos == -1) {
            lastSegment = path;
        } else {
            lastSegment = path.substring(pos + 1);
        }
        if (lastSegment.isEmpty()) {
            return true;
        }
        int ext = lastSegment.lastIndexOf('.');
        if (ext == -1) {
            //no extension
            return true;
        }
        final String extension = lastSegment.substring(ext + 1, lastSegment.length());
        if (config.isDefaultAllowed()) {
            return !config.getDisallowed().contains(extension);
        } else {
            return config.getAllowed().contains(extension);
        }
    }

    public FileCache getFileCache() {
        return fileCache;
    }

    public void setFileCache(final FileCache fileCache) {
        this.fileCache = fileCache;
    }
}
