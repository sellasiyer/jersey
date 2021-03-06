/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.server.internal.routing;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import javax.ws.rs.core.Response;

import org.glassfish.jersey.internal.ExceptionMapperFactory;
import org.glassfish.jersey.internal.util.collection.Ref;
import org.glassfish.jersey.message.MessageBodyWorkers;
import org.glassfish.jersey.message.internal.MessageBodyFactory;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.RequestContextBuilder;
import org.glassfish.jersey.process.Inflector;
import org.glassfish.jersey.process.internal.RequestInvoker;
import org.glassfish.jersey.process.internal.RequestScope;
import org.glassfish.jersey.server.InvokerBuilder;
import org.glassfish.jersey.server.ServerModule;
import org.glassfish.jersey.server.internal.routing.RouterModule.RootRouteBuilder;
import org.glassfish.jersey.spi.ExceptionMappers;
import org.glassfish.jersey.uri.PathPattern;

import org.glassfish.hk2.HK2;
import org.glassfish.hk2.Services;
import org.glassfish.hk2.TypeLiteral;
import org.glassfish.hk2.inject.Injector;

import org.jvnet.hk2.annotations.Inject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertEquals;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * PathPattern routing test.
 *
 * @author Paul Sandoz
 */
@RunWith(Parameterized.class)
public class PathPatternRoutingTest {

    private static final URI BASE_URI = URI.create("http://localhost:8080/base/");

    @Parameterized.Parameters
    public static List<String[]> testUriSuffixes() {
        return Arrays.asList(new String[][]{
                {"a/b/c", "B-c-b-a"},
                {"a/b/c/", "B-c-b-a"},
                {"a/d/e", "B-e-d-a"},
                {"a/d/e/", "B-e-d-a"},

                {"a/d", "B--d-a"},
                {"a/d/", "B--d-a"},

                {"m/b/n", "B-n-b-m"},
                {"m/b/n/", "B-n-b-m"},
                {"x/d/y", "B-y-d-x"},
                {"x/d/y/", "B-y-d-x"}
        });
    }

    @Inject
    private RootRouteBuilder<PathPattern> routeBuilder;
    private RequestInvoker<ContainerRequest, ContainerResponse> invoker; // will be manually injected in the setupApplication()
    private RequestScope requestScope; // will be manually injected in the setupApplication()
    private final String uriSuffix;
    private final String expectedResponse;

    public PathPatternRoutingTest(String uriSuffix, String expectedResponse) {
        this.uriSuffix = uriSuffix;
        this.expectedResponse = expectedResponse;
    }

    @Before
    public void setupApplication() {
        Services services = HK2.get().create(null, new ServerModule());

        final Ref<MessageBodyWorkers> workers = services.forContract(new TypeLiteral<Ref<MessageBodyWorkers>>(){}).get();
        workers.set(new MessageBodyFactory(services));
        final Ref<ExceptionMappers> mappers = services.forContract(new TypeLiteral<Ref<ExceptionMappers>>(){}).get();
        mappers.set(new ExceptionMapperFactory(services));

        Injector injector = services.forContract(Injector.class).get();
        injector.inject(this);

        final InvokerBuilder invokerBuilder = injector.inject(InvokerBuilder.class);
        Router inflection = Routers.asTreeAcceptor(new Inflector<ContainerRequest, ContainerResponse>() {

            @Override
            public ContainerResponse apply(ContainerRequest requestContext) {
                return new ContainerResponse(requestContext, Response.ok("B").build());
            }
        });

        this.invoker = invokerBuilder.build(routeBuilder.root(
                routeBuilder.route("{p1}").to(LastPathSegmentTracingFilter.class)
                        .to(routeBuilder.route("b").to(LastPathSegmentTracingFilter.class)
                                .to(routeBuilder.route(new PathPattern("{p2}", PathPattern.RightHandPath.capturingZeroSegments)).to(LastPathSegmentTracingFilter.class).to(inflection)))
                        .to(routeBuilder.route("d").to(LastPathSegmentTracingFilter.class)
                                .to(routeBuilder.route(new PathPattern("{p3 : [^/]+}", PathPattern.RightHandPath.capturingZeroSegments)).to(LastPathSegmentTracingFilter.class).to(inflection))
                                        // this is how resource methods on sub-resources get mapped:
                                .to(routeBuilder.route(PathPattern.EMPTY_PATTERN).to(LastPathSegmentTracingFilter.class).to(inflection))
                                .to(routeBuilder.route(new PathPattern("/", PathPattern.RightHandPath.capturingZeroSegments)).to(LastPathSegmentTracingFilter.class).to(inflection)))
                        .build()));

        this.requestScope = injector.inject(RequestScope.class);
    }

    @Test
    public void testPathPatternRouting() throws Exception {
        final ContainerRequest req =
                RequestContextBuilder.from(BASE_URI, URI.create(BASE_URI.getPath() + uriSuffix), "GET").build();
        Future<ContainerResponse> res = requestScope.runInScope(
                new Callable<ListenableFuture<ContainerResponse>>() {

                    @Override
                    public ListenableFuture<ContainerResponse> call() throws Exception {
                        return invoker.apply(req);
                    }
                });

        assertEquals(expectedResponse, res.get().getEntity());
    }
}
