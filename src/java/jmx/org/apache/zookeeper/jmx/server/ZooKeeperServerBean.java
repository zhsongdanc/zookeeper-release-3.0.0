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

package org.apache.zookeeper.jmx.server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;

import org.apache.zookeeper.Version;
import org.apache.zookeeper.jmx.ZKMBeanInfo;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.ZooKeeperServer;

/**
 * This class implements the zookeeper server MBean interface.
 */
public class ZooKeeperServerBean implements ZooKeeperServerMXBean, ZKMBeanInfo {
    private Date startTime=new Date();
    private ZooKeeperServer zooKeeperServer;

    public ZooKeeperServerBean() {
    }
    public ZooKeeperServerBean(ZooKeeperServer zooKeeperServer) {
        this.zooKeeperServer = zooKeeperServer;
    }

    public String getClientPort() {
        ZooKeeperServer zks = getZooKeeperServer();
        if( zks == null ) {
            return null;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress() + ":"
                    + zks.getClientPort();
        } catch (UnknownHostException e) {
            return "localhost:" + zks.getClientPort();
        }
    }

    public String getName() {
        return "StandaloneServer";
    }

    public boolean isHidden() {
        return false;
    }

    public String getStartTime() {
        return startTime.toString();
    }

    public String getVersion() {
        return Version.getFullVersion();
    }

    public long getAvgRequestLatency() {
        return ServerStats.getInstance().getAvgLatency();
    }

    public long getMaxRequestLatency() {
        return ServerStats.getInstance().getMaxLatency();
    }

    public long getMinRequestLatency() {
        return ServerStats.getInstance().getMinLatency();
    }

    public long getOutstandingRequests() {
        return ServerStats.getInstance().getOutstandingRequests();
    }

    public long getPacketsReceived() {
        return ServerStats.getInstance().getPacketsReceived();
    }

    public long getPacketsSent() {
        return ServerStats.getInstance().getPacketsSent();
    }

    public void resetLatency() {
        ServerStats.getInstance().resetLatency();
    }

    public void resetMaxLatency() {
        ServerStats.getInstance().resetMaxLatency();
    }

    public void resetStatistics() {
        ServerStats.getInstance().resetRequestCounters();
        ServerStats.getInstance().resetLatency();
    }

    public ZooKeeperServer getZooKeeperServer() {
        return zooKeeperServer;
    }

    public void setZooKeeperServer(ZooKeeperServer zooKeeperServer) {
        this.zooKeeperServer = zooKeeperServer;
    }

}
