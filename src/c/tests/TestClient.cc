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

#include <cppunit/extensions/HelperMacros.h>
#include "CppAssertHelper.h"

#include <stdlib.h>
#include <sys/select.h>

#include "CollectionUtil.h"
#include "ThreadingUtil.h"

using namespace Util;

#include "Vector.h"
using namespace std;

#include <cstring>
#include <list>

#include <zookeeper.h>

#ifdef THREADED
    static void yield(zhandle_t *zh, int i)
    {
        sleep(i);
    }
#else
    static void yield(zhandle_t *zh, int seconds)
    {
        int fd;
        int interest;
        int events;
        struct timeval tv;
        int rc;
        time_t expires = time(0) + seconds; 
        time_t timeLeft = seconds;
        fd_set rfds, wfds, efds;
        FD_ZERO(&rfds);
        FD_ZERO(&wfds);
        FD_ZERO(&efds);

        while(timeLeft >= 0) {
            zookeeper_interest(zh, &fd, &interest, &tv);
            if (fd != -1) {
                if (interest&ZOOKEEPER_READ) {
                    FD_SET(fd, &rfds);
                } else {
                    FD_CLR(fd, &rfds);
                }
                if (interest&ZOOKEEPER_WRITE) {
                    FD_SET(fd, &wfds);
                } else {
                    FD_CLR(fd, &wfds);
                }
            } else {
                fd = 0;
            }
            FD_SET(0, &rfds);
            if (tv.tv_sec > timeLeft) {
                tv.tv_sec = timeLeft;
            }
            rc = select(fd+1, &rfds, &wfds, &efds, &tv);
            timeLeft = expires - time(0);
            events = 0;
            if (FD_ISSET(fd, &rfds)) {
                events |= ZOOKEEPER_READ;
            }
            if (FD_ISSET(fd, &wfds)) {
                events |= ZOOKEEPER_WRITE;
            }
            zookeeper_process(zh, events);
        }
    }
#endif

typedef struct evt {
    string path;
    int type;
} evt_t;

typedef struct watchCtx {
private:
    list<evt_t> events;
public:
    bool connected;
    zhandle_t *zh;
    Mutex mutex;

    watchCtx() {
        connected = false;
        zh = 0;
    }
    ~watchCtx() {
        if (zh) {
            zookeeper_close(zh);
            zh = 0;
        }
    }

    evt_t getEvent() {
        evt_t evt;
        mutex.acquire();
        evt = events.front();
        events.pop_front();
        mutex.release();
        return evt;
    }

    int countEvents() {
        int count;
        mutex.acquire();
        count = events.size();
        mutex.release();
        return count;
    }

    void putEvent(evt_t evt) {
        mutex.acquire();
        events.push_back(evt);
        mutex.release();
    }

    bool waitForConnected(zhandle_t *zh) {
        time_t expires = time(0) + 10;
        while(!connected && time(0) < expires) {
            yield(zh, 1);
        }
        return connected;
    }
    bool waitForDisconnected(zhandle_t *zh) {
        time_t expires = time(0) + 15;
        while(connected && time(0) < expires) {
            yield(zh, 1);
        }
        return !connected;
    }
} watchctx_t; 

class Zookeeper_simpleSystem : public CPPUNIT_NS::TestFixture
{
    CPPUNIT_TEST_SUITE(Zookeeper_simpleSystem);
    CPPUNIT_TEST(testAsyncWatcherAutoReset);
#ifdef THREADED
    CPPUNIT_TEST(testPing);
    CPPUNIT_TEST(testWatcherAutoResetWithGlobal);
    CPPUNIT_TEST(testWatcherAutoResetWithLocal);
#endif
    CPPUNIT_TEST_SUITE_END();

    static void watcher(zhandle_t *, int type, int state, const char *path,void*v){
        watchctx_t *ctx = (watchctx_t*)v;

        if (state == ZOO_CONNECTED_STATE) {
            ctx->connected = true;
        } else {
            ctx->connected = false;
        }
        if (type != ZOO_SESSION_EVENT) {
            evt_t evt;
            evt.path = path;
            evt.type = type;
            ctx->putEvent(evt);
        }
    }

    static const char hostPorts[];

    const char *getHostPorts() {
        return hostPorts;
    }

    zhandle_t *createClient(watchctx_t *ctx) {
        zhandle_t *zk = zookeeper_init(hostPorts, watcher, 10000, 0,
                                       ctx, 0);
        ctx->zh = zk;
        sleep(1);
        return zk;
    }
    
public:

#define ZKSERVER_CMD "./tests/zkServer.sh"

    void setUp()
    {
        char cmd[1024];
        sprintf(cmd, "%s startClean %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);
    }
    

    void startServer() {
        char cmd[1024];
        sprintf(cmd, "%s start %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void stopServer() {
        tearDown();
    }

    void tearDown()
    {
        char cmd[1024];
        sprintf(cmd, "%s stop %s", ZKSERVER_CMD, getHostPorts());
        CPPUNIT_ASSERT(system(cmd) == 0);
    }

    void testPing()
    {
        watchctx_t ctxIdle;
        watchctx_t ctxWC;
        zhandle_t *zkIdle = createClient(&ctxIdle);
        zhandle_t *zkWatchCreator = createClient(&ctxWC);
        int rc;
        char path[80];
        CPPUNIT_ASSERT(zkIdle);
        CPPUNIT_ASSERT(zkWatchCreator);
        for(int i = 0; i < 30; i++) {
            sprintf(path, "/%i", i);
            rc = zoo_create(zkWatchCreator, path, "", 0, &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
        }

        for(int i = 0; i < 30; i++) {
            sprintf(path, "/%i", i);
            struct Stat stat;
            rc = zoo_exists(zkIdle, path, 1, &stat);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
        }

        for(int i = 0; i < 30; i++) {
            sprintf(path, "/%i", i);
            usleep(500000);
            rc = zoo_delete(zkWatchCreator, path, -1);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
        }
        struct Stat stat;
        CPPUNIT_ASSERT_EQUAL(ZNONODE, zoo_exists(zkIdle, "/0", 0, &stat));
    }

    bool waitForEvent(zhandle_t *zh, watchctx_t *ctx, int seconds) {
        time_t expires = time(0) + seconds;
        while(ctx->countEvents() == 0 && time(0) < expires) {
            yield(zh, 1);
        }
        return ctx->countEvents() > 0;
    }

#define COUNT 100
    
    static zhandle_t *async_zk;

    static void statCompletion(int rc, const struct Stat *stat, const void *data) {
        CPPUNIT_ASSERT_EQUAL((int)data, rc);
    }

    static void stringCompletion(int rc, const char *value, const void *data) {
        char *path = (char*)data;
        
        if (rc == ZCONNECTIONLOSS && path) {
            // Try again
            rc = zoo_acreate(async_zk, path, "", 0,  &ZOO_OPEN_ACL_UNSAFE, 0, stringCompletion, 0);
        } else if (rc != ZOK) {
            // fprintf(stderr, "rc = %d with path = %s\n", rc, (path ? path : "null"));
        }
        if (path) {
            free(path);
        }
    }

    void testAsyncWatcherAutoReset()
    {
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        watchctx_t lctx[COUNT];
        int i;
        char path[80];
        int rc;
        evt_t evt;

        async_zk = zk;
        for(i = 0; i < COUNT; i++) {
            sprintf(path, "/%d", i);
            rc = zoo_awexists(zk, path, watcher, &lctx[i], statCompletion, (void*)ZNONODE);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
        }

        yield(zk, 0);

        for(i = 0; i < COUNT/2; i++) {
            sprintf(path, "/%d", i);
            rc = zoo_acreate(zk, path, "", 0,  &ZOO_OPEN_ACL_UNSAFE, 0, stringCompletion, strdup(path));
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
        }

        yield(zk, 3);
        for(i = 0; i < COUNT/2; i++) {
            sprintf(path, "/%d", i);
            CPPUNIT_ASSERT_MESSAGE(path, waitForEvent(zk, &lctx[i], 5));
            evt = lctx[i].getEvent();
            CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path.c_str(), ZOO_CREATED_EVENT, evt.type);
            CPPUNIT_ASSERT_EQUAL(string(path), evt.path);
        }

        for(i = COUNT/2 + 1; i < COUNT*10; i++) {
            sprintf(path, "/%d", i);
            rc = zoo_acreate(zk, path, "", 0,  &ZOO_OPEN_ACL_UNSAFE, 0, stringCompletion, strdup(path));
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
        }

        yield(zk, 1);
        stopServer();
        CPPUNIT_ASSERT(ctx.waitForDisconnected(zk));
        startServer();
        CPPUNIT_ASSERT(ctx.waitForConnected(zk));
        yield(zk, 3);
        for(i = COUNT/2+1; i < COUNT; i++) {
            sprintf(path, "/%d", i);
            CPPUNIT_ASSERT_MESSAGE(path, waitForEvent(zk, &lctx[i], 5));
            evt = lctx[i].getEvent();
            CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_CREATED_EVENT, evt.type);
            CPPUNIT_ASSERT_EQUAL(string(path), evt.path);
        }
    }

    void testWatcherAutoReset(zhandle_t *zk, watchctx_t *ctxGlobal, 
                              watchctx_t *ctxLocal)
    {
        bool isGlobal = (ctxGlobal == ctxLocal);
        int rc;
        struct Stat stat;
        char buf[1024];
        int blen;
        struct String_vector strings;
        const char *testName;

        rc = zoo_create(zk, "/watchtest", "", 0, 
                        &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL(ZOK, rc);
        rc = zoo_create(zk, "/watchtest/child", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, ZOO_EPHEMERAL, 0, 0);
        CPPUNIT_ASSERT_EQUAL(ZOK, rc);
        if (isGlobal) {
            testName = "GlobalTest";
            rc = zoo_get_children(zk, "/watchtest", 1, &strings);
            deallocate_String_vector(&strings);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
            blen = sizeof(buf);
            rc = zoo_get(zk, "/watchtest/child", 1, buf, &blen, &stat);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
            rc = zoo_exists(zk, "/watchtest/child2", 1, &stat);
            CPPUNIT_ASSERT_EQUAL(ZNONODE, rc);
        } else {
            testName = "LocalTest";
            rc = zoo_wget_children(zk, "/watchtest", watcher, ctxLocal,
                                 &strings);
            deallocate_String_vector(&strings);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
            blen = sizeof(buf);
            rc = zoo_wget(zk, "/watchtest/child", watcher, ctxLocal,
                         buf, &blen, &stat);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
            rc = zoo_wexists(zk, "/watchtest/child2", watcher, ctxLocal,
                            &stat);
            CPPUNIT_ASSERT_EQUAL(ZNONODE, rc);
        }
        
        CPPUNIT_ASSERT(ctxLocal->countEvents() == 0);

        stopServer();
        CPPUNIT_ASSERT_MESSAGE(testName, ctxGlobal->waitForDisconnected(zk));
        startServer();
        CPPUNIT_ASSERT_MESSAGE(testName, ctxLocal->waitForConnected(zk));

        CPPUNIT_ASSERT(ctxLocal->countEvents() == 0);
        rc = zoo_set(zk, "/watchtest/child", "1", 1, -1);
        CPPUNIT_ASSERT_EQUAL(ZOK, rc);
        rc = zoo_create(zk, "/watchtest/child2", "", 0,
                        &ZOO_OPEN_ACL_UNSAFE, 0, 0, 0);
        CPPUNIT_ASSERT_EQUAL(ZOK, rc);

        CPPUNIT_ASSERT_MESSAGE(testName, waitForEvent(zk, ctxLocal, 5));
        
        evt_t evt = ctxLocal->getEvent();
        CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_CHANGED_EVENT, evt.type);
        CPPUNIT_ASSERT_EQUAL(string("/watchtest/child"), evt.path);

        // The create will trigget the get children and the
        // exists watches
        evt = ctxLocal->getEvent();
        CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_CREATED_EVENT, evt.type);
        CPPUNIT_ASSERT_EQUAL(string("/watchtest/child2"), evt.path);
        evt = ctxLocal->getEvent();
        CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_CHILD_EVENT, evt.type);
        CPPUNIT_ASSERT_EQUAL(string("/watchtest"), evt.path);

        // Make sure Pings are giving us problems
        sleep(5);

        CPPUNIT_ASSERT(ctxLocal->countEvents() == 0);
        
        stopServer();
        CPPUNIT_ASSERT_MESSAGE(testName, ctxGlobal->waitForDisconnected(zk));
        startServer();
        CPPUNIT_ASSERT_MESSAGE(testName, ctxGlobal->waitForConnected(zk));

        if (isGlobal) {
            testName = "GlobalTest";
            rc = zoo_get_children(zk, "/watchtest", 1, &strings);
            deallocate_String_vector(&strings);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
            blen = sizeof(buf);
            rc = zoo_get(zk, "/watchtest/child", 1, buf, &blen, &stat);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
            rc = zoo_exists(zk, "/watchtest/child2", 1, &stat);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
        } else {
            testName = "LocalTest";
            rc = zoo_wget_children(zk, "/watchtest", watcher, ctxLocal,
                                 &strings);
            deallocate_String_vector(&strings);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
            blen = sizeof(buf);
            rc = zoo_wget(zk, "/watchtest/child", watcher, ctxLocal,
                         buf, &blen, &stat);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
            rc = zoo_wexists(zk, "/watchtest/child2", watcher, ctxLocal,
                            &stat);
            CPPUNIT_ASSERT_EQUAL(ZOK, rc);
        }

        zoo_delete(zk, "/watchtest/child2", -1);

        CPPUNIT_ASSERT_MESSAGE(testName, waitForEvent(zk, ctxLocal, 5));
        
        evt = ctxLocal->getEvent();
        CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_DELETED_EVENT, evt.type);
        CPPUNIT_ASSERT_EQUAL(string("/watchtest/child2"), evt.path);

        evt = ctxLocal->getEvent();
        CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_CHILD_EVENT, evt.type);
        CPPUNIT_ASSERT_EQUAL(string("/watchtest"), evt.path);

        stopServer();
        CPPUNIT_ASSERT_MESSAGE(testName, ctxGlobal->waitForDisconnected(zk));
        startServer();
        CPPUNIT_ASSERT_MESSAGE(testName, ctxLocal->waitForConnected(zk));

        zoo_delete(zk, "/watchtest/child", -1);
        zoo_delete(zk, "/watchtest", -1);

        CPPUNIT_ASSERT_MESSAGE(testName, waitForEvent(zk, ctxLocal, 5));
        
        evt = ctxLocal->getEvent();
        CPPUNIT_ASSERT_EQUAL_MESSAGE(evt.path, ZOO_DELETED_EVENT, evt.type);
        CPPUNIT_ASSERT_EQUAL(string("/watchtest/child"), evt.path);

        // Make sure nothing is straggling
        sleep(1);
        CPPUNIT_ASSERT(ctxLocal->countEvents() == 0);
    }        

    void testWatcherAutoResetWithGlobal()
    {
        watchctx_t ctx;
        zhandle_t *zk = createClient(&ctx);
        testWatcherAutoReset(zk, &ctx, &ctx);
    }

    void testWatcherAutoResetWithLocal()
    {
        watchctx_t ctx;
        watchctx_t lctx;
        zhandle_t *zk = createClient(&ctx);
        testWatcherAutoReset(zk, &ctx, &lctx);
    }
};

zhandle_t *Zookeeper_simpleSystem::async_zk;
const char Zookeeper_simpleSystem::hostPorts[] = "127.0.0.1:22181";
CPPUNIT_TEST_SUITE_REGISTRATION(Zookeeper_simpleSystem);
