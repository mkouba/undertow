/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.attribute;

import io.undertow.security.api.SecurityContext;
import io.undertow.server.HttpServerExchange;

/**
 * The remote user
 *
 * @author Stuart Douglas
 */
public class RemoteUserAttribute implements ExchangeAttribute {

    public static final String REMOTE_USER_SHORT = "%u";
    public static final String REMOTE_USER = "%{REMOTE_USER}";

    public static final ExchangeAttribute INSTANCE = new RemoteUserAttribute();

    private RemoteUserAttribute() {

    }

    @Override
    public String readAttribute(final HttpServerExchange exchange) {
        SecurityContext sc = exchange.getSecurityContext();
        if (sc == null || !sc.isAuthenticated()) {
            return null;
        }
        return sc.getAuthenticatedAccount().getPrincipal().getName();
    }

    @Override
    public void writeAttribute(final HttpServerExchange exchange, final String newValue) throws ReadOnlyAttributeException {
        throw new ReadOnlyAttributeException("Remote user", newValue);
    }

    public static final class Builder implements ExchangeAttributeBuilder {

        @Override
        public String name() {
            return "Remote user";
        }

        @Override
        public ExchangeAttribute build(final String token) {
            if (token.equals(REMOTE_USER) || token.equals(REMOTE_USER_SHORT)) {
                return RemoteUserAttribute.INSTANCE;
            }
            return null;
        }
    }
}
