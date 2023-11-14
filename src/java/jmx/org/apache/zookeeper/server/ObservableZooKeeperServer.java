/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.io.IOException;


import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.util.ObserverManager;
import org.apache.zookeeper.server.util.ServerObserver;

/**
 * The observable server broadcast notifications when its state changes. 
 * 
 * The code interested in receiving the notification must implement 
 * the {@link ServerObserver} interface and register the instance with 
 * {@link ObserverManager}.
 */
public class ObservableZooKeeperServer extends ZooKeeperServer{

    private ZooKeeperObserverNotifier notifier=new ZooKeeperObserverNotifier(this);
    public ObservableZooKeeperServer(FileTxnSnapLog logFactory, 
            int tickTime,DataTreeBuilder treeBuilder) throws IOException {
        super(logFactory, tickTime,treeBuilder);
    }
    public ObservableZooKeeperServer(FileTxnSnapLog logFactory,
            DataTreeBuilder treeBuilder) throws IOException {
        super(logFactory,treeBuilder);
    }

    public void shutdown() {
        notifier.notifyShutdown();
        super.shutdown();
    }

    public void startup() throws IOException, InterruptedException {
        super.startup();
        notifier.notifyStarted();
    }
}
