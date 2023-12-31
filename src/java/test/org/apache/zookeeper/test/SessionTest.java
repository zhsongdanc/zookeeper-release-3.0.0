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

package org.apache.zookeeper.test;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.Test;

public class SessionTest extends TestCase implements Watcher {
    protected static final Logger LOG = Logger.getLogger(SessionTest.class);

    private static final String HOSTPORT = "127.0.0.1:33299";
    private NIOServerCnxn.Factory serverFactory;
    
    private CountDownLatch startSignal;

    @Override
    protected void setUp() throws Exception {
        LOG.info("STARTING " + getName());

        ServerStats.registerAsConcrete();

        File tmpDir = ClientBase.createTmpDir();

        ClientBase.setupTestEnv();
        ZooKeeperServer zs = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        serverFactory = new NIOServerCnxn.Factory(PORT);
        serverFactory.startup(zs);

        assertTrue("waiting for server up",
                   ClientBase.waitForServerUp(HOSTPORT,
                                              CONNECTION_TIMEOUT));
    }
    @Override
    protected void tearDown() throws Exception {
        serverFactory.shutdown();
        assertTrue("waiting for server down",
                   ClientBase.waitForServerDown(HOSTPORT,
                                                CONNECTION_TIMEOUT));

        ServerStats.unregister();
        LOG.info("FINISHED " + getName());
    }

    private static class CountdownWatcher implements Watcher {
        volatile CountDownLatch clientConnected = new CountDownLatch(1);

        public void process(WatchedEvent event) {
            if (event.getState() == KeeperState.SyncConnected) {
                clientConnected.countDown();
            }
        }
    }

    private DisconnectableZooKeeper createClient()
        throws IOException, InterruptedException
    {
        CountdownWatcher watcher = new CountdownWatcher();
        DisconnectableZooKeeper zk =
                new DisconnectableZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, watcher);
        if(!watcher.clientConnected.await(CONNECTION_TIMEOUT,
                TimeUnit.MILLISECONDS))
        {
            fail("Unable to connect to server");
        }

        return zk;
    }

// FIXME this test is failing due to client close race condition fixing in separate patch for ZOOKEEPER-63
//    /**
//     * this test checks to see if the sessionid that was created for the
//     * first zookeeper client can be reused for the second one immidiately
//     * after the first client closes and the new client resues them.
//     * @throws IOException
//     * @throws InterruptedException
//     * @throws KeeperException
//     */
//    public void testSessionReuse() throws IOException, InterruptedException {
//        ZooKeeper zk = createClient();
//
//        long sessionId = zk.getSessionId();
//        byte[] passwd = zk.getSessionPasswd();
//        zk.close();
//
//        zk.close();
//
//        LOG.info("Closed first session");
//
//        startSignal = new CountDownLatch(1);
//        zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, this,
//                sessionId, passwd);
//        startSignal.await();
//
//        LOG.info("Opened reuse");
//
//        assertEquals(sessionId, zk.getSessionId());
//
//        zk.close();
//    }

    @Test
    /**
     * This test verifies that when the session id is reused, and the original
     * client is disconnected, but not session closed, that the server
     * will remove ephemeral nodes created by the original session.
     */
    public void testSession()
        throws IOException, InterruptedException, KeeperException
    {
        DisconnectableZooKeeper zk = createClient();
        zk.create("/e", new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.EPHEMERAL);
        LOG.info("zk with session id 0x" + Long.toHexString(zk.getSessionId())
                + " was destroyed!");

        // disconnect the client by killing the socket, not sending the
        // session disconnect to the server as usual. This allows the test
        // to verify disconnect handling
        zk.disconnect();

        Stat stat = new Stat();
        startSignal = new CountDownLatch(1);
        zk = new DisconnectableZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, this,
                               zk.getSessionId(),
                               zk.getSessionPasswd());
        startSignal.await();

        LOG.info("zk with session id 0x" + Long.toHexString(zk.getSessionId())
                 + " was created!");
        zk.getData("/e", false, stat);
        LOG.info("After get data /e");
        zk.close();

        zk = createClient();
        assertEquals(null, zk.exists("/e", false));
        LOG.info("before close zk with session id 0x"
                + Long.toHexString(zk.getSessionId()) + "!");
        zk.close();
    }

    public void process(WatchedEvent event) {
        LOG.info("Event:" + event.getState() + " " + event.getType() + " " + event.getPath());
        if (event.getState() == KeeperState.SyncConnected
                && startSignal.getCount() > 0)
        {
            startSignal.countDown();
        }
    }

}
