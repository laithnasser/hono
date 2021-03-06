/*******************************************************************************
 * Copyright (c) 2016, 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.service.auth.device;

import java.util.Objects;

import javax.security.auth.x500.X500Principal;

import org.eclipse.hono.util.CredentialsConstants;

import io.vertx.core.json.JsonObject;


/**
 * Helper class to generate an authentication ID for an X.509 certificate provided by
 * a device during authentication.
 * <p>
 * The authentication ID is the certificate's <em>subject DN</em> in
 * RFC 2253 format.
 *
 */
public class SubjectDnCredentials extends AbstractDeviceCredentials {

    private SubjectDnCredentials(final String tenantId, final String authId, final JsonObject clientContext) {
        super(tenantId, authId, clientContext);
    }

    /**
     * Creates credentials for a tenant and subject DN.
     * 
     * @param tenantId The tenant that the device belongs to.
     * @param subjectDn The subject DN of the device's client certificate.
     * @return The credentials.
     * @throws NullPointerException if any of the parameters are {@code null}.
     */
    public static SubjectDnCredentials create(final String tenantId, final String subjectDn) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(subjectDn);
        return create(tenantId, subjectDn, new JsonObject());
    }

    /**
     * Creates credentials for a tenant and subject DN.
     * 
     * @param tenantId The tenant that the device belongs to.
     * @param subjectDn The subject DN of the device's client certificate.
     * @param clientContext The client context that can be used to get credentials from the Credentials API.
     * @return The credentials.
     * @throws NullPointerException if any of the parameters are {@code null}.
     */
    public static SubjectDnCredentials create(final String tenantId, final String subjectDn,
            final JsonObject clientContext) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(subjectDn);
        Objects.requireNonNull(clientContext);
        return create(tenantId, new X500Principal(subjectDn), clientContext);
    }

    /**
     * Creates credentials for a tenant and subject DN.
     * 
     * @param tenantId The tenant that the device belongs to.
     * @param subjectDn The subject DN of the device's client certificate.
     * @return The credentials.
     * @throws NullPointerException if any of the parameters are {@code null}.
     */
    public static SubjectDnCredentials create(final String tenantId, final X500Principal subjectDn) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(subjectDn);
        return create(tenantId, subjectDn, new JsonObject());
    }

    /**
     * Creates credentials for a tenant and subject DN.
     * 
     * @param tenantId The tenant that the device belongs to.
     * @param subjectDn The subject DN of the device's client certificate.
     * @param clientContext The client context that can be used to get credentials from the Credentials API.
     * @return The credentials.
     * @throws NullPointerException if any of the parameters are {@code null}.
     */
    public static SubjectDnCredentials create(final String tenantId, final X500Principal subjectDn,
            final JsonObject clientContext) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(subjectDn);
        Objects.requireNonNull(clientContext);
        return new SubjectDnCredentials(tenantId, subjectDn.getName(X500Principal.RFC2253), clientContext);
    }

    /**
     * {@inheritDoc}
     * 
     * @return Always {@link CredentialsConstants#SECRETS_TYPE_X509_CERT}.
     */
    @Override
    public String getType() {
        return CredentialsConstants.SECRETS_TYPE_X509_CERT;
    }
}
