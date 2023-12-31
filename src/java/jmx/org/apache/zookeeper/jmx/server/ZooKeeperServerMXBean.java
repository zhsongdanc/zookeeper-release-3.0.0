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

/**
 * Zookeeper server MBean.
 */
public interface ZooKeeperServerMXBean {
    /**
     * @return the server socket port number
     */
    public String getClientPort();
    /**
     * @return the zookeeper server version
     */
    public String getVersion();
    /**
     * @return time the server was started
     */
    public String getStartTime();
    /**
     * @return min request latency in ms
     */
    public long getMinRequestLatency();
    /**
     * @return average request latency in ms
     */
    public long getAvgRequestLatency();
    /**
     * @return max request latency in ms
     */
    public long getMaxRequestLatency();
    /**
     * @return number of packets received so far
     */
    public long getPacketsReceived();
    /**
     * @return number of packets sent so far
     */
    public long getPacketsSent();
    /**
     * @return number of outstanding requests.
     */
    public long getOutstandingRequests();
    
    /**
     * Reset packet and latency statistics 
     */
    public void resetStatistics();
    /**
     * Reset min/avg/max latency statistics
     */
    public void resetLatency();
    /**
     * Reset max latency statistics only.
     */
    public void resetMaxLatency();
}
