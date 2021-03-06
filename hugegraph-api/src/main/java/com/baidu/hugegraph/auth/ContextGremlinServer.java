/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.auth;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

import org.apache.commons.lang3.SystemUtils;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.server.GraphManager;
import org.apache.tinkerpop.gremlin.server.GremlinServer;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.util.ServerGremlinExecutor;
import org.apache.tinkerpop.gremlin.server.util.ThreadFactoryUtil;
import org.apache.tinkerpop.gremlin.structure.Graph;

import com.baidu.hugegraph.HugeException;
import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.auth.HugeGraphAuthProxy.Context;
import com.baidu.hugegraph.auth.HugeGraphAuthProxy.ContextThreadPoolExecutor;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 * GremlinServer with custom ServerGremlinExecutor, which can pass Context
 */
public class ContextGremlinServer extends GremlinServer {

    @SuppressWarnings("deprecation")
    public ContextGremlinServer(final Settings settings) {
        /*
         * TODO: use GremlinServer(Settings, ExecutorService)
         * which can be obtained from https://github.com/apache/tinkerpop/pull/813
         *
         * NOTE: should call GremlinServer::configureMetrics() but it's private
         * settings.optionalMetrics().ifPresent(GremlinServer::configureMetrics)
         */
        super(create(settings));
    }

    public void injectAuthGraph() {
        HugeGraphAuthProxy.setContext(Context.admin());

        GraphManager manager = this.getServerGremlinExecutor()
                                   .getGraphManager();
        for (String name : manager.getGraphNames()) {
            Graph graph = manager.getGraph(name);
            graph = new HugeGraphAuthProxy((HugeGraph) graph);
            manager.putGraph(name, graph);
        }
    }

    public void injectTraversalSource(String prefix) {
        GraphManager manager = this.getServerGremlinExecutor()
                                   .getGraphManager();
        for (String graph : manager.getGraphNames()) {
            GraphTraversalSource g = manager.getGraph(graph).traversal();
            String gName = prefix + graph;
            if (manager.getTraversalSource(gName) != null) {
                throw new HugeException(
                          "Found existing name '%s' in global bindings, " +
                          "it may lead to gremlin query error.", gName);
            }
            // Add a traversal source for all graphs with customed rule.
            manager.putTraversalSource(gName, g);
        }
    }

    static ServerGremlinExecutor<EventLoopGroup> create(Settings settings) {
        if (settings.gremlinPool == 0) {
            settings.gremlinPool = Runtime.getRuntime().availableProcessors();
        }

        ExecutorService service = newGremlinExecutorService(settings);

        EventLoopGroup group;
        boolean isEpollEnabled = settings.useEpollEventLoop &&
                                 SystemUtils.IS_OS_LINUX;
        ThreadFactory factory = ThreadFactoryUtil.create("worker-%d");
        if (isEpollEnabled) {
            group = new EpollEventLoopGroup(settings.threadPoolWorker, factory);
        } else {
            group = new NioEventLoopGroup(settings.threadPoolWorker, factory);
        }

        return new ServerGremlinExecutor<>(settings, service,
                                           group, EventLoopGroup.class);
    }

    static ExecutorService newGremlinExecutorService(Settings settings) {
        int size = settings.gremlinPool;
        ThreadFactory factory = ThreadFactoryUtil.create("exec-%d");
        return new ContextThreadPoolExecutor(size, size, factory);
    }
}
