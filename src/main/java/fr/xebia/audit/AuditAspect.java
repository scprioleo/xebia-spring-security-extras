/*
 * Copyright 2002-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.xebia.audit;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

@Aspect
public class AuditAspect {

    protected static class RootObject {

        private final Object[] args;

        private final Object invokedObject;

        private final Object returned;

        private final Throwable throwned;

        private RootObject(Object invokedObject, Object[] args, Object returned, Throwable throwned) {
            super();
            this.invokedObject = invokedObject;
            this.args = args;
            this.returned = returned;
            this.throwned = throwned;
        }

        public Object[] getArgs() {
            return args;
        }

        public Object getInvokedObject() {
            return invokedObject;
        }

        public Object getReturned() {
            return returned;
        }

        public Throwable getThrowned() {
            return throwned;
        }

    }

    private static class TemplateParserContext implements ParserContext {

        public String getExpressionPrefix() {
            return "#{";
        }

        public String getExpressionSuffix() {
            return "}";
        }

        public boolean isTemplate() {
            return true;
        }
    }

    protected static void appendThrowableCauses(Throwable throwable, String separator, StringBuilder toAppendTo) {
        List<Throwable> alreadyAppendedThrowables = new ArrayList<Throwable>();

        while (throwable != null) {
            // append
            toAppendTo.append(throwable.toString());
            alreadyAppendedThrowables.add(throwable);

            // cause
            Throwable cause = throwable.getCause();
            if (cause == null || alreadyAppendedThrowables.contains(cause)) {
                break;
            } else {
                throwable = cause;
                toAppendTo.append(separator);
            }
        }
    }

    private SimpleDateFormat dateFormatPrototype = new SimpleDateFormat("yyyy/MM/dd-HH:mm:ss:SSS");

    private Map<String, Expression> expressionCache = new ConcurrentHashMap<String, Expression>();

    private ExpressionParser expressionParser = new SpelExpressionParser();

    private Logger logger = LoggerFactory.getLogger("fr.xebia.audit");

    private ParserContext parserContext = new TemplateParserContext();

    protected String buildMessage(String template, Object invokedObject, Object[] args, Object returned, Throwable throwned) {
        try {
            Expression expression = expressionCache.get(template);
            if (expression == null) {
                expression = expressionParser.parseExpression(template, parserContext);
                expressionCache.put(template, expression);
            }

            String evaluatedMessage = expression.getValue(new RootObject(invokedObject, args, returned, throwned), String.class);

            StringBuilder msg = new StringBuilder();

            SimpleDateFormat simpleDateFormat = (SimpleDateFormat) dateFormatPrototype.clone();
            msg.append(simpleDateFormat.format(new Date()));

            msg.append(" ").append(evaluatedMessage);

            if (throwned != null) {
                msg.append(" throwing '");
                appendThrowableCauses(throwned, ", ", msg);
            }
            msg.append(" by ");
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null) {
                msg.append("anonymous");
            } else {
                msg.append(authentication.getName());
                if (authentication.getDetails() instanceof WebAuthenticationDetails) {
                    WebAuthenticationDetails details = (WebAuthenticationDetails) authentication.getDetails();
                    msg.append(" coming from " + details.getRemoteAddress());
                }
            }
            return msg.toString();
        } catch (RuntimeException e) {
            StringBuilder msg = new StringBuilder("Exception evaluating template '" + template + "': ");
            appendThrowableCauses(e, ", ", msg);
            return msg.toString();
        }
    }

    @Around(value = "execution(* *(..)) && @annotation(audited)", argNames = "pjp,audited")
    public Object logMessage(ProceedingJoinPoint pjp, Audited audited) throws Throwable {

        try {
            Object returned = pjp.proceed();
            String message = buildMessage(audited.message(), pjp.getThis(), pjp.getArgs(), returned, null);
            logger.info(message);
            return returned;
        } catch (Throwable t) {
            String message = buildMessage(audited.message(), pjp.getThis(), pjp.getArgs(), null, t);
            logger.warn(message);
            throw t;
        }
    }
}
