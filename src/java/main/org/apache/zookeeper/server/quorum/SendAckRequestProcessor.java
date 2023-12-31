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

import org.apache.log4j.Logger;

import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;

public class SendAckRequestProcessor implements RequestProcessor {
    private static final Logger LOG = Logger.getLogger(SendAckRequestProcessor.class);
    
    Follower follower;

    SendAckRequestProcessor(Follower follower) {
        this.follower = follower;
    }

    public void processRequest(Request si) {
        if(si.type != OpCode.sync){
            QuorumPacket qp = new QuorumPacket(Leader.ACK, si.hdr.getZxid(), null,
                null);
            try {
                follower.writePacket(qp);
            } catch (IOException e) {
                LOG.error("FIXMSG",e);
            }
        }
    }

    public void shutdown() {
        // Nothing needed
    }

}
