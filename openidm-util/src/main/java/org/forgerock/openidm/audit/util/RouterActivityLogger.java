/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2015 ForgeRock AS. All Rights Reserved
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */

package org.forgerock.openidm.audit.util;

import static org.forgerock.json.JsonValue.field;
import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;

import java.util.List;

import org.forgerock.audit.events.AuditEvent;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.Connection;
import org.forgerock.json.resource.ConnectionFactory;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.Request;
import org.forgerock.json.resource.RequestType;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.openidm.core.IdentityServer;
import org.forgerock.services.context.Context;
import org.forgerock.services.context.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates an audit activity log message using the router.
 */
public class RouterActivityLogger implements ActivityLogger {

    /**
     * Setup logging for the {@link ActivityLogger}.
     */
    final static Logger logger = LoggerFactory.getLogger(RouterActivityLogger.class);

    public static final String ACTIVITY_EVENT_NAME = "activity";
    public static final String AUDIT_ACTIVITY_PATH = "audit/activity";
    public static final String OPENIDM_AUDIT_LOG_FULL_OBJECTS = "openidm.audit.logFullObjects";

    private final ConnectionFactory connectionFactory;
    private final boolean suspendException;
    private final boolean logFullObjects;

    /**
     * Creates an AuditLogger to create activity messages on the router.
     *
     * @param connectionFactory The {@link ConnectionFactory} to use.
     */
    public RouterActivityLogger(ConnectionFactory connectionFactory) {
        this(connectionFactory,
                "suspend".equals(IdentityServer.getInstance().getProperty(ActivityLogger.class.getName().toLowerCase())));
    }

    /**
     * Creates an AuditLogger to create activity messages on the router.
     *
     * @param connectionFactory The {@link ConnectionFactory} to use.
     * @param suspendException whether to throw Exceptions on failure to log or not
     */
    public RouterActivityLogger(ConnectionFactory connectionFactory, boolean suspendException) {
        this.connectionFactory = connectionFactory;
        this.suspendException = suspendException;

        IdentityServer identityServer = IdentityServer.getInstance();
        this.logFullObjects = Boolean.valueOf(identityServer.getProperty(OPENIDM_AUDIT_LOG_FULL_OBJECTS, "false"));
    }

    /**
     * Grab authenticationId from security context, if one exists.
     *
     * @param context the context to possibly get the authenticationId from
     * @return authenticationId from the security context
     */
    private String getRequester(Context context) {
        return context.containsContext(SecurityContext.class)
            ? context.asContext(SecurityContext.class).getAuthenticationId()
            : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void log(Context context, Request request, String message, String objectId,
                    JsonValue before, JsonValue after, Status status) throws ResourceException {

        if (null == request) {
            throw new NullPointerException("Request can not be null when audit.");
        }

        try {
            final AuditEvent auditEvent = OpenIDMActivityAuditEventBuilder.auditEventBuilder()
                    .transactionIdFromContext(context)
                    .timestamp(System.currentTimeMillis())
                    .eventName(ACTIVITY_EVENT_NAME)
                    .userId(getRequester(context))
                    .runAs(getRequester(context))
                    .operationFromCrestRequest(request)
                    .before(getJsonForLog(before, request.getRequestType()))
                    .after(getJsonForLog(after, request.getRequestType()))
                    .changedFields(getChangedFields("getChangedWatchedFields", before, after, context))
                    .revision(getRevision(before, after))
                    .message(message)
                    .objectId(objectId)
                    .passwordChanged(getChangedFields("getChangedPasswordFields", before, after, context).length > 0)
                    .status(status)
                    .toEvent();

            connectionFactory.getConnection().create(context,
                    Requests.newCreateRequest(AUDIT_ACTIVITY_PATH, auditEvent.getValue()));
        } catch (ResourceException ex) {
            if (suspendException) {
                // log on exception if we're suspending the exception-propagation
                logger.warn("Failed to write activity log {}", ex);
            } else {
                // let the caller handle/log the exception
                throw ex;
            }
        } catch (Exception ex) {
            if (suspendException) {
                // log on exception if we're suspending the exception-propagation
                logger.warn("Failed to write activity log {}", ex);
            } else {
                // let the caller handle/log the exception
                throw new InternalServerErrorException(ex.getMessage(), ex);
            }
        }
    }

    /**
     * This calls Audit service to utilize its get changed field abilities.
     * Determining the changed fields is left to the AuditService since it has the ability to utilize the CryptoService.
     *
     * @param auditAction The action that determines which watch filter to apply to the fields.
     * @param before The object before changes.
     * @param after The object after changes.
     * @param context passed to the action call on audit service.
     * @return fields that have changes.
     * @throws ResourceException
     */
    //TODO once dependency is resolved, first param should be AuditService.AuditAction rather than string.
    private String[] getChangedFields(String auditAction, JsonValue before, JsonValue after,
            Context context) throws ResourceException {
        ActionRequest actionRequest = Requests.newActionRequest("audit", auditAction);
        actionRequest.setContent(
                json(object(
                        field("before", before),
                        field("after", after)
                )));
        Connection connection = connectionFactory.getConnection();
        JsonValue action = connection.action(context, actionRequest).getJsonContent();
        List<String> changes = action.asList(String.class);
        return changes.toArray(new String[changes.size()]);
    }

    /**
     * By default, READ and QUERY requests will not log the full object,
     * unless overridden by logFullObjects.
     *
     * @param value       the value to be returned if not a READ or QUERY, and not overridden by logFullObjects.
     * @param requestType the type of request.
     * @return the value to be returned if not a READ or QUERY, and not overridden by logFullObjects; otherwise a
     * {@link JsonValue} wrapped null.
     */
    private JsonValue getJsonForLog(JsonValue value, RequestType requestType) {
        boolean isReadOrQueryRequest = RequestType.READ.equals(requestType) || RequestType.QUERY.equals(requestType);

        if (logFullObjects || !isReadOrQueryRequest) {
            return value != null ? value : json(null);
        }

        return json(null);
    }

    /**
     * Pulls the revision from after if it isn't null, otherwise from before if it isn't null, otherwise null.
     * Revision is expected to be a string field.
     *
     * @param before json before any changes were made
     * @param after  json after any changes were made
     * @return revision
     */
    private String getRevision(JsonValue before, JsonValue after) {
        if (after != null && after.get(ResourceResponse.FIELD_CONTENT_REVISION).isString()) {
            return after.get(ResourceResponse.FIELD_CONTENT_REVISION).asString();
        }
        if (before != null && before.get(ResourceResponse.FIELD_CONTENT_REVISION).isString()) {
            return before.get(ResourceResponse.FIELD_CONTENT_REVISION).asString();
        }
        return null;
    }
}
