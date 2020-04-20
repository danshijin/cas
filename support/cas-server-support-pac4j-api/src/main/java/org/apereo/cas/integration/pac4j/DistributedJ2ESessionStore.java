package org.apereo.cas.integration.pac4j;

import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.ticket.TicketFactory;
import org.apereo.cas.ticket.TransientSessionTicket;
import org.apereo.cas.ticket.TransientSessionTicketFactory;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.apereo.cas.web.support.CookieUtils;
import org.apereo.cas.web.support.gen.CookieRetrievingCookieGenerator;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.pac4j.core.context.JEEContext;
import org.pac4j.core.context.session.SessionStore;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

/**
 * This is {@link DistributedJ2ESessionStore}.
 *
 * @author Misagh Moayyed
 * @author Jerome LELEU
 * @since 6.1.0
 */
@Transactional(transactionManager = "ticketTransactionManager")
@Slf4j
public class DistributedJ2ESessionStore implements SessionStore<JEEContext> {
    private static final String SESSION_ID_IN_REQUEST_ATTRIBUTE = "sessionIdInRequestAttribute";

    private final TicketRegistry ticketRegistry;

    private final TicketFactory ticketFactory;

    private final CookieRetrievingCookieGenerator cookieGenerator;

    public DistributedJ2ESessionStore(final TicketRegistry ticketRegistry, final TicketFactory ticketFactory,
                                      final CasConfigurationProperties casProperties) {
        this.ticketRegistry = ticketRegistry;
        this.ticketFactory = ticketFactory;
        val cookie = casProperties.getSessionReplication().getCookie();
        val context = CookieUtils.buildCookieGenerationContext(cookie);
        this.cookieGenerator = new CookieRetrievingCookieGenerator(context);
    }

    @Override
    public String getOrCreateSessionId(final JEEContext context) {
        var sessionId = getSessionId(context);
        if (StringUtils.isBlank(sessionId)) {
            sessionId = UUID.randomUUID().toString();
            LOGGER.trace("Generated session id: [{}]", sessionId);
            cookieGenerator.addCookie(context.getNativeRequest(), context.getNativeResponse(), sessionId);
            context.setRequestAttribute(SESSION_ID_IN_REQUEST_ATTRIBUTE, sessionId);
        }
        return sessionId;
    }


    private String getSessionId(final JEEContext context) {
        var sessionId = (String) context.getRequestAttribute(SESSION_ID_IN_REQUEST_ATTRIBUTE).orElse(null);
        if (StringUtils.isBlank(sessionId)) {
            sessionId = cookieGenerator.retrieveCookieValue(context.getNativeRequest());
        }
        LOGGER.trace("Generated session id: [{}]", sessionId);
        return sessionId;
    }

    @Override
    public Optional get(final JEEContext context, final String key) {
        LOGGER.trace("Getting key: [{}]", key);
        val ticket = getTransientSessionTicketForSession(context);
        if (ticket == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ticket.getProperties().get(key));
    }

    @Override
    public void set(final JEEContext context, final String key, final Object value) {
        LOGGER.trace("Setting key: [{}]", key);
        val sessionId = getOrCreateSessionId(context);

        val properties = new HashMap<String, Serializable>();
        if (value instanceof Serializable) {
            properties.put(key, (Serializable) value);
        } else if (value != null) {
            LOGGER.trace("Object value [{}] assigned to [{}] is not serializable and may not be part of the ticket [{}]",
                value, key, sessionId);
        }

        var ticket = getTransientSessionTicketForSession(context);
        if (value == null && ticket != null) {
            ticket.getProperties().remove(key);
            this.ticketRegistry.updateTicket(ticket);
        } else if (ticket == null) {
            val transientFactory = (TransientSessionTicketFactory) this.ticketFactory.get(TransientSessionTicket.class);
            ticket = transientFactory.create(sessionId, properties);
            this.ticketRegistry.addTicket(ticket);
        } else {
            ticket.getProperties().putAll(properties);
            this.ticketRegistry.updateTicket(ticket);
        }
    }

    @Override
    public boolean destroySession(final JEEContext context) {
        try {
            val sessionId = getOrCreateSessionId(context);
            val ticketId = TransientSessionTicketFactory.normalizeTicketId(sessionId);
            this.ticketRegistry.deleteTicket(ticketId);
            cookieGenerator.removeCookie(context.getNativeResponse());
            LOGGER.trace("Removes session cookie and ticket: [{}]", ticketId);
            return true;
        } catch (final Exception e) {
            LOGGER.trace(e.getMessage(), e);
        }
        return false;
    }

    @Override
    public Optional getTrackableSession(final JEEContext context) {
        val sessionId = getOrCreateSessionId(context);
        LOGGER.trace("Track sessionId: [{}]", sessionId);
        return Optional.of(sessionId);
    }

    @Override
    public Optional<SessionStore<JEEContext>> buildFromTrackableSession(final JEEContext context, final Object trackableSession) {
        context.setRequestAttribute(SESSION_ID_IN_REQUEST_ATTRIBUTE, trackableSession);
        LOGGER.trace("Force sessionId: [{}]", trackableSession);
        return Optional.of(this);
    }

    @Override
    public boolean renewSession(final JEEContext context) {
        return false;
    }

    private TransientSessionTicket getTransientSessionTicketForSession(final JEEContext context) {
        val sessionId = getOrCreateSessionId(context);
        val ticketId = TransientSessionTicketFactory.normalizeTicketId(sessionId);

        LOGGER.trace("fetching ticket: {}", ticketId);
        val ticket = this.ticketRegistry.getTicket(ticketId, TransientSessionTicket.class);
        if (ticket == null || ticket.isExpired()) {
            LOGGER.trace("Ticket [{}] does not exist or is expired", ticketId);
            return null;
        }
        return ticket;
    }
}
