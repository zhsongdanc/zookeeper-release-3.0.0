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

package org.apache.zookeeper.server.quorum;

import java.io.IOException;

import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.PrepRequestProcessor;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.SessionTrackerImpl;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;

/**
 * 
 * Just like the standard ZooKeeperServer. We just replace the request
 * processors: PrepRequestProcessor -> ProposalRequestProcessor ->
 * CommitProcessor -> Leader.ToBeAppliedRequestProcessor ->
 * FinalRequestProcessor
 */
public class LeaderZooKeeperServer extends ZooKeeperServer {
    private QuorumPeer self;

    CommitProcessor commitProcessor;

    /**
     * @param port
     * @param dataDir
     * @throws IOException
     */
    LeaderZooKeeperServer(FileTxnSnapLog logFactory,QuorumPeer self,
            DataTreeBuilder treeBuilder) throws IOException {
        super(logFactory, self.tickTime,treeBuilder);
        this.self = self;
    }

    public Leader getLeader(){
        return self.leader;
    }
    
    @Override
    protected void setupRequestProcessors() {
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);
        RequestProcessor toBeAppliedProcessor = new Leader.ToBeAppliedRequestProcessor(
                finalProcessor, getLeader().toBeApplied);
        commitProcessor = new CommitProcessor(toBeAppliedProcessor,
                Integer.toString(getClientPort()), false);
        RequestProcessor proposalProcessor = new ProposalRequestProcessor(this,
                commitProcessor);
        firstProcessor = new PrepRequestProcessor(this, proposalProcessor);
    }

    @Override
    public int getGlobalOutstandingLimit() {
        return super.getGlobalOutstandingLimit() / (self.getQuorumSize() - 1);
    }
    
    @Override
    protected void createSessionTracker() {
        sessionTracker = new SessionTrackerImpl(this, sessionsWithTimeouts,
                tickTime, self.getId());
    }


    public boolean touch(long sess, int to) {
        return sessionTracker.touchSession(sess, to);
    }

    public void setZxid(long zxid) {
        hzxid = zxid;
    }
}
