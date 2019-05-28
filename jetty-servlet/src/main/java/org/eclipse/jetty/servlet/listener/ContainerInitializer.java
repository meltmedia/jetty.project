//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.servlet.listener;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * Utility Methods for manual execution of {@link javax.servlet.ServletContainerInitializer} when
 * using Embedded Jetty.
 */
public final class ContainerInitializer
{
    /**
     * Utility Method to allow for manual execution of {@link javax.servlet.ServletContainerInitializer} when
     * using Embedded Jetty.
     *
     * <code>
     * ServletContextHandler context = new ServletContextHandler();
     * ServletContainerInitializer corpSci = new MyCorporateSCI();
     * context.addEventListener(ContainerInitializer.asContextListener(corpSci));
     * </code>
     *
     * <p>
     * The {@link ServletContainerInitializer} will have its {@link ServletContainerInitializer#onStartup(Set, ServletContext)}
     * method called with the manually configured list of {@code Set<Class<?>> c} set.
     * In other words, this usage does not perform bytecode or annotation scanning against the classes in
     * your {@code ServletContextHandler} or {@code WebAppContext}.
     * </p>
     *
     * @param sci the {@link ServletContainerInitializer} to call
     * @return the {@link ServletContextListener} wrapping the SCI
     * @see SCIAsContextListener#addClasses(Class[])
     * @see SCIAsContextListener#addClasses(String...)
     */
    public static SCIAsContextListener asContextListener(ServletContainerInitializer sci)
    {
        return new SCIAsContextListener(sci);
    }

    public static class SCIAsContextListener implements ServletContextListener
    {
        private final ServletContainerInitializer sci;
        private Set<String> classNames;
        private Set<Class<?>> classes = new HashSet<>();
        private Consumer<ServletContext> postOnStartupConsumer;

        public SCIAsContextListener(ServletContainerInitializer sci)
        {
            this.sci = sci;
        }

        /**
         * Add classes to be passed to the {@link ServletContainerInitializer#onStartup(Set, ServletContext)}  call.
         * <p>
         *     Note that these classes will be loaded using the context classloader for the ServletContext
         *     initialization phase.
         * </p>
         *
         * @param classNames the class names to load and pass into the {@link ServletContainerInitializer#onStartup(Set, ServletContext)}  call
         * @return this configured {@link SCIAsContextListener} instance.
         */
        public SCIAsContextListener addClasses(String... classNames)
        {
            if (this.classNames == null)
            {
                this.classNames = new HashSet<>();
            }
            this.classNames.addAll(Arrays.asList(classNames));
            return this;
        }

        /**
         * Add classes to be passed to the {@link ServletContainerInitializer#onStartup(Set, ServletContext)}  call.
         * <p>
         *     Note that these classes will exist on the classloader that was used to call this method.
         *     If you want the classes to be loaded using the context classloader for the ServletContext
         *     then use the String form of the classes via the {@link #addClasses(String...)} method.
         * </p>
         *
         * @param classes the classes to pass into the {@link ServletContainerInitializer#onStartup(Set, ServletContext)}  call
         * @return this configured {@link SCIAsContextListener} instance.
         */
        public SCIAsContextListener addClasses(Class<?>... classes)
        {
            this.classes.addAll(Arrays.asList(classes));
            return this;
        }

        /**
         * Add a optional consumer to execute once the {@link ServletContainerInitializer#onStartup(Set, ServletContext)} has
         * been called successfully.
         * <p>
         *     This would be for actions to perform on a ServletContext once this specific SCI has completed
         *     its execution.  Actions that would require specific configurations that the SCI provides to be present on the
         *     ServletContext to function properly.
         * </p>
         * <p>
         *     This consumer is typically used for Embedded Jetty users to configure Jetty for their specific needs.
         * </p>
         *
         *
         * @param consumer the consumer to execute after the SCI has executed
         * @return this configured {@link SCIAsContextListener} instance.
         */
        public SCIAsContextListener setPostOnStartupConsumer(Consumer<ServletContext> consumer)
        {
            this.postOnStartupConsumer = consumer;
            return this;
        }

        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            ServletContext servletContext = sce.getServletContext();
            try
            {
                sci.onStartup(getClasses(), servletContext);
                if (postOnStartupConsumer != null)
                {
                    postOnStartupConsumer.accept(servletContext);
                }
            }
            catch (RuntimeException rte)
            {
                throw rte;
            }
            catch (Throwable cause)
            {
                throw new RuntimeException(cause);
            }
        }

        public Set<Class<?>> getClasses()
        {
            if (classNames != null && !classNames.isEmpty())
            {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();

                for (String className : classNames)
                {
                    try
                    {
                        Class<?> clazz = cl.loadClass(className);
                        classes.add(clazz);
                    }
                    catch (ClassNotFoundException e)
                    {
                        throw new RuntimeException("Unable to find class: " + className, e);
                    }
                }
            }

            return classes;
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            // ignore
        }
    }
}
