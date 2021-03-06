/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openejb.cdi;

import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.webbeans.config.OWBLogConst;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.context.AbstractContextsService;
import org.apache.webbeans.context.ApplicationContext;
import org.apache.webbeans.context.ConversationContext;
import org.apache.webbeans.context.DependentContext;
import org.apache.webbeans.context.RequestContext;
import org.apache.webbeans.context.SessionContext;
import org.apache.webbeans.context.SingletonContext;
import org.apache.webbeans.conversation.ConversationImpl;
import org.apache.webbeans.conversation.ConversationManager;
import org.apache.webbeans.el.ELContextStore;
import org.apache.webbeans.spi.ContextsService;
import org.apache.webbeans.spi.ConversationService;
import org.apache.webbeans.web.context.ServletRequestContext;
import org.apache.webbeans.web.intercept.RequestScopedBeanInterceptorHandler;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.ContextException;
import javax.enterprise.context.Conversation;
import javax.enterprise.context.ConversationScoped;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.context.SessionScoped;
import javax.enterprise.context.spi.Context;
import javax.inject.Singleton;
import javax.servlet.ServletRequestEvent;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;

public class CdiAppContextsService extends AbstractContextsService implements ContextsService {

    private static final Logger logger = Logger.getInstance(LogCategory.OPENEJB.createChild("cdi"), CdiAppContextsService.class);

    private final ThreadLocal<RequestContext> requestContext = new ThreadLocal<RequestContext>();

    private final ThreadLocal<SessionContext> sessionContext = new ThreadLocal<SessionContext>();
    private final UpdatableSessionContextManager sessionCtxManager = new UpdatableSessionContextManager();

    /**
     * Conversation context manager
     */
    private final ThreadLocal<ConversationContext> conversationContext;

    private final DependentContext dependentContext = new DependentContext();

    private final ApplicationContext applicationContext = new ApplicationContext();

    private final SingletonContext singletonContext = new SingletonContext();

    private final WebBeansContext webBeansContext;

    private static final ThreadLocal<Collection<Runnable>> endRequestRunnables = new ThreadLocal<Collection<Runnable>>() {
        @Override
        protected Collection<Runnable> initialValue() {
            return new ArrayList<Runnable>();
        }
    };


    public CdiAppContextsService() {
        this(WebBeansContext.currentInstance(), WebBeansContext.currentInstance().getOpenWebBeansConfiguration().supportsConversation());
    }

    public CdiAppContextsService(final WebBeansContext wbc, final boolean supportsConversation) {
        if (wbc != null) {
            webBeansContext = wbc;
        } else {
            webBeansContext = WebBeansContext.currentInstance();
        }

        dependentContext.setActive(true);
        if (supportsConversation) {
            conversationContext = new ThreadLocal<ConversationContext>();
        } else {
            conversationContext = null;
        }
        applicationContext.setActive(true);
        singletonContext.setActive(true);
    }

    private void endRequest() {
        for (final Runnable r : endRequestRunnables.get()) {
            try {
                r.run();
            } catch (final Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
        endRequestRunnables.remove();
    }

    public static void pushRequestReleasable(final Runnable runnable) {
        endRequestRunnables.get().add(runnable);
    }

    @Override
    public void init(final Object initializeObject) {
        //Start application context
        startContext(ApplicationScoped.class, initializeObject);

        //Start signelton context
        startContext(Singleton.class, initializeObject);
    }

    public void destroy(final Object destroyObject) {
        //Destroy application context
        endContext(ApplicationScoped.class, destroyObject);

        //Destroy singleton context
        endContext(Singleton.class, destroyObject);

        removeThreadLocals();
    }

    public void removeThreadLocals() {
        //Remove thread locals
        //for preventing memory leaks
        requestContext.set(null);
        requestContext.remove();
        sessionContext.set(null);
        sessionContext.remove();

        if (null != conversationContext) {
            conversationContext.set(null);
            conversationContext.remove();
        }
    }

    @Override
    public void endContext(final Class<? extends Annotation> scopeType, final Object endParameters) {

        if (supportsContext(scopeType)) {
            if (scopeType.equals(RequestScoped.class)) {
                destroyRequestContext();
            } else if (scopeType.equals(SessionScoped.class)) {
                destroySessionContext((HttpSession) endParameters);
            } else if (scopeType.equals(ApplicationScoped.class)) {
                destroyApplicationContext();
            } else if (scopeType.equals(Dependent.class)) { //NOPMD
                // Do nothing
            } else if (scopeType.equals(Singleton.class)) {
                destroySingletonContext();
            } else if (supportsConversation() && scopeType.equals(ConversationScoped.class)) {
                destroyConversationContext();
            } else {
                if (logger.isWarningEnabled()) {
                    logger.warning("CDI-OpenWebBeans container in OpenEJB does not support context scope "
                            + scopeType.getSimpleName()
                            + ". Scopes @Dependent, @RequestScoped, @ApplicationScoped and @Singleton are supported scope types");
                }
            }
        }

    }

    @Override
    public Context getCurrentContext(final Class<? extends Annotation> scopeType) {
        if (scopeType.equals(RequestScoped.class)) {
            return getRequestContext();
        } else if (scopeType.equals(SessionScoped.class)) {
            return getSessionContext();
        } else if (scopeType.equals(ApplicationScoped.class)) {
            return getApplicationContext();
        } else if (supportsConversation() && scopeType.equals(ConversationScoped.class)) {
            return getConversationContext();
        } else if (scopeType.equals(Dependent.class)) {
            return dependentContext;
        } else if (scopeType.equals(Singleton.class)) {
            return getSingletonContext();
        }

        return null;
    }

    @Override
    public void startContext(final Class<? extends Annotation> scopeType, final Object startParameter) throws ContextException {
        if (supportsContext(scopeType)) {
            if (scopeType.equals(RequestScoped.class)) {
                initRequestContext((ServletRequestEvent) startParameter);
            } else if (scopeType.equals(SessionScoped.class)) {
                initSessionContext((HttpSession) startParameter);
            } else if (scopeType.equals(ApplicationScoped.class)) {
                initApplicationContext();
            } else if (scopeType.equals(Dependent.class)) {
                initSingletonContext();
            } else if (scopeType.equals(Singleton.class)) { //NOPMD
                // Do nothing
            } else if (supportsConversation() && scopeType.equals(ConversationScoped.class)) {
                initConversationContext((ConversationContext) startParameter);
            } else {
                if (logger.isWarningEnabled()) {
                    logger.warning("CDI-OpenWebBeans container in OpenEJB does not support context scope "
                            + scopeType.getSimpleName()
                            + ". Scopes @Dependent, @RequestScoped, @ApplicationScoped and @Singleton are supported scope types");
                }
            }
        }
    }

    private void initSingletonContext() {
        singletonContext.setActive(true);
    }

    private void initApplicationContext() { // in case contexts are stop/start
        applicationContext.setActive(true);
    }

    @Override
    public boolean supportsContext(final Class<? extends Annotation> scopeType) {
        return scopeType.equals(RequestScoped.class)
                || scopeType.equals(SessionScoped.class)
                || scopeType.equals(ApplicationScoped.class)
                || scopeType.equals(Dependent.class)
                || scopeType.equals(Singleton.class)
                || scopeType.equals(ConversationScoped.class) && supportsConversation();

    }

    private void initRequestContext(final ServletRequestEvent event) {
        final RequestContext rq = new ServletRequestContext();
        rq.setActive(true);

        requestContext.set(rq);// set thread local
        if (event != null) {
            final HttpServletRequest request = (HttpServletRequest) event.getServletRequest();
            ((ServletRequestContext) rq).setServletRequest(request);

            if (request != null) {
                //Re-initialize thread local for session
                final HttpSession session = request.getSession(false);

                if (session != null) {
                    initSessionContext(session);
                }
            }
        }
    }

    private void destroyRequestContext() {
        // execute request tasks
        endRequest();

        if (supportsConversation()) { // OWB-595
            cleanupConversation();
        }

        //Get context
        final RequestContext context = getRequestContext();

        //Destroy context
        if (context != null) {
            context.destroy();
        }

        // clean up the EL caches after each request
        final ELContextStore elStore = ELContextStore.getInstance(false);
        if (elStore != null) {
            elStore.destroyELContextStore();
        }

        //Clear thread locals - only for request to let user do with deltaspike start(session, request)restart(request)...stop()
        requestContext.remove();

        RequestScopedBeanInterceptorHandler.removeThreadLocals();
    }

    private void cleanupConversation() {
        if (webBeansContext.getService(ConversationService.class) == null) {
            return;
        }

        final ConversationContext conversationContext = getConversationContext();
        if (conversationContext == null) {
            return;
        }

        final ConversationManager conversationManager = webBeansContext.getConversationManager();
        final Conversation conversation = conversationManager.getConversationBeanReference();
        if (conversation == null) {
            return;
        }

        if (conversation.isTransient()) {
            webBeansContext.getContextsService().endContext(ConversationScoped.class, null);
        } else {
            final ConversationImpl conversationImpl = (ConversationImpl) conversation;
            conversationImpl.updateTimeOut();
            conversationImpl.setInUsed(false);
        }
    }

    /**
     * Creates the session context at the session start.
     *
     * @param session http session object
     */
    private void initSessionContext(final HttpSession session) {
        if (session == null) {
            // no session -> no SessionContext
            return;
        }

        final String sessionId = session.getId();
        //Current context
        SessionContext currentSessionContext = sessionCtxManager.getSessionContextWithSessionId(sessionId);

        //No current context
        if (currentSessionContext == null) {
            currentSessionContext = newSessionContext(session);
            sessionCtxManager.addNewSessionContext(sessionId, currentSessionContext);
        }
        //Activate
        currentSessionContext.setActive(true);

        //Set thread local
        sessionContext.set(currentSessionContext);
    }

    private SessionContext newSessionContext(final HttpSession session) {
        final String classname = SystemInstance.get().getComponent(ThreadSingletonService.class).sessionContextClass();
        if (classname != null) {
            try {
                final Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(classname);
                try {
                    final Constructor<?> constr = clazz.getConstructor(HttpSession.class);
                    return (SessionContext)constr.newInstance(session);
                } catch (final Exception e) {
                    return (SessionContext) clazz.newInstance();
                }

            } catch (final Exception e) {
                logger.error("Can't instantiate " + classname + ", using default session context", e);
            }
        }
        return new SessionContext();
    }

    /**
     * Destroys the session context and all of its components at the end of the
     * session.
     *
     * @param session http session object
     */
    private void destroySessionContext(final HttpSession session) {
        if (session != null) {
            //Get current session context
            final SessionContext context = sessionContext.get();

            //Destroy context
            if (context != null) {
                context.destroy();
            }

            //Clear thread locals
            sessionContext.set(null);
            sessionContext.remove();

            //Remove session from manager
            sessionCtxManager.removeSessionContextWithSessionId(session.getId());
        }
    }

    //we don't have initApplicationContext

    private void destroyApplicationContext() {
        applicationContext.destroy();
    }

    private void destroySingletonContext() {
        singletonContext.destroy();
    }

    /**
     * Initialize conversation context.
     *
     * @param context context
     */
    private void initConversationContext(final ConversationContext context) {
        if (webBeansContext.getService(ConversationService.class) == null) {
            return;
        }

        if (context == null) {
            if (conversationContext.get() == null) {
                final ConversationContext newContext = new ConversationContext();
                newContext.setActive(true);

                conversationContext.set(newContext);
            } else {
                conversationContext.get().setActive(true);
            }

        } else {
            context.setActive(true);
            conversationContext.set(context);
        }
    }

    /**
     * Destroy conversation context.
     */
    private void destroyConversationContext() {
        if (webBeansContext.getService(ConversationService.class) == null) {
            return;
        }

        final ConversationContext context = getConversationContext();

        if (context != null) {
            context.destroy();
        }

        if (null != conversationContext) {
            conversationContext.set(null);
            conversationContext.remove();
        }
    }


    private RequestContext getRequestContext() {
        return requestContext.get();
    }

    private Context getSessionContext() {
        SessionContext context = sessionContext.get();
        if (context == null || !context.isActive()) {
            lazyStartSessionContext();
            context = sessionContext.get();
        }
        return context;
    }

    /**
     * Gets application context.
     *
     * @return application context
     */
    private ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    /**
     * Gets singleton context.
     *
     * @return singleton context
     */
    private SingletonContext getSingletonContext() {
        return singletonContext;
    }

    /**
     * Get current conversation ctx.
     *
     * @return conversation context
     */
    private ConversationContext getConversationContext() {
        return conversationContext.get();
    }

    private Context lazyStartSessionContext() {

        if (logger.isDebugEnabled()) {
            logger.debug(">lazyStartSessionContext");
        }

        final Context webContext = null;
        final Context context = getCurrentContext(RequestScoped.class);
        if (context instanceof ServletRequestContext) {
            final ServletRequestContext requestContext = (ServletRequestContext) context;
            final HttpServletRequest servletRequest = requestContext.getServletRequest();
            if (null != servletRequest) { // this could be null if there is no active request context
                try {
                    final HttpSession currentSession = servletRequest.getSession();
                    initSessionContext(currentSession);

                    /*
                    final FailOverService failoverService = webBeansContext.getService(FailOverService.class);
                    if (failoverService != null && failoverService.isSupportFailOver()) {
                        failoverService.sessionIsInUse(currentSession);
                    }
                    */

                    if (logger.isDebugEnabled()) {
                        logger.debug("Lazy SESSION context initialization SUCCESS");
                    }
                } catch (final Exception e) {
                    logger.error(OWBLogConst.ERROR_0013, e);
                }

            } else {
                logger.warning("Could NOT lazily initialize session context because NO active request context");
            }
        } else {
            logger.warning("Could NOT lazily initialize session context because of " + context + " RequestContext");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("<lazyStartSessionContext " + webContext);
        }
        return webContext;
    }

    private boolean supportsConversation() {
        return conversationContext != null;
    }

    public void updateSessionIdMapping(final String oldId, final String newId) {
        sessionCtxManager.updateSessionIdMapping(oldId, newId);
    }

    public State saveState() {
        return new State(requestContext.get(), sessionContext.get(), conversationContext.get());
    }

    public State restoreState(final State state) {
        final State old = saveState();
        requestContext.set(state.request);
        sessionContext.set(state.session);
        conversationContext.set(state.conversation);
        return old;
    }

    public static class State {
        private final RequestContext request;
        private final SessionContext session;
        private final ConversationContext conversation;

        public State(final RequestContext request, final SessionContext session, final ConversationContext conversation) {
            this.request = request;
            this.session = session;
            this.conversation = conversation;
        }
    }
}
