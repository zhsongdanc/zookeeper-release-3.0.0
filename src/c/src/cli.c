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

#include <zookeeper.h>
#include <proto.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/select.h>
#include <sys/time.h>
#include <time.h>
#include <errno.h>
#include <assert.h>

#ifdef YCA
#include <yca/yca.h>
#endif

#define _LL_CAST_ (long long)

static zhandle_t *zh;
static clientid_t myid;
static const char *clientIdFile = 0;
struct timeval startTime;
static char cmd[1024];
static int batchMode=0;

static int to_send=0;
static int sent=0;
static int recvd=0;

static int shutdownThisThing=0;

static __attribute__ ((unused)) void 
printProfileInfo(struct timeval start, struct timeval end,int thres,const char* msg)
{
  int delay=(end.tv_sec*1000+end.tv_usec/1000)-
    (start.tv_sec*1000+start.tv_usec/1000);
  if(delay>thres)
    fprintf(stderr,"%s: execution time=%dms\n",msg,delay);
}

void watcher(zhandle_t *zzh, int type, int state, const char *path,void* context) {
    fprintf(stderr,"Watcher %d state = %d for %s\n", type, state, (path ? path: "null"));
    if (type == ZOO_SESSION_EVENT) {
        if (state == ZOO_CONNECTED_STATE) {
            const clientid_t *id = zoo_client_id(zh);
            if (myid.client_id == 0|| myid.client_id != id->client_id) {
                myid = *id;
                fprintf(stderr, "Got a new id: %llx\n", _LL_CAST_ myid.client_id);
                if (clientIdFile) {
                    FILE *fh = fopen(clientIdFile, "w");
                    if (!fh) {
                        perror(clientIdFile);
                    } else {
                        int rc = fwrite(&myid, sizeof(myid), 1, fh);
                        if (rc != sizeof(myid)) {
                            perror("writing client id");
                        }
                        fclose(fh);
                    }
                }
            }
        } else if (state == ZOO_AUTH_FAILED_STATE) {
            fprintf(stderr, "Authentication failure. Shutting down...\n");
            zookeeper_close(zh);
            shutdownThisThing=1;
            zh=0;
        } else if (state == ZOO_EXPIRED_SESSION_STATE) {
            fprintf(stderr, "Session expired. Shutting down...\n");
            zookeeper_close(zh);
            shutdownThisThing=1;
            zh=0;
        }
    }
}

void dumpStat(const struct Stat *stat) {
    char tctimes[40];
    char tmtimes[40];
    time_t tctime;
    time_t tmtime;

    if (!stat) {
        fprintf(stderr,"null\n");
        return;
    }
    tctime = stat->ctime/1000;
    tmtime = stat->mtime/1000;
    fprintf(stderr, "\tctime = %s\tczxid=%llx\n"
    "\tmtime=%s\tmzxid=%llx\n"
    "\tversion=%x\taversion=%x\n"
    "\tephemeralOwner = %llx\n",
    ctime_r(&tctime, tctimes), _LL_CAST_ stat->czxid, ctime_r(&tmtime, tmtimes),
    _LL_CAST_ stat->mzxid,
    (unsigned int)stat->version, (unsigned int)stat->aversion,
    _LL_CAST_ stat->ephemeralOwner);
}

void my_string_completion(int rc, const char *name, const void *data) {
    fprintf(stderr, "[%s]: rc = %d\n", (char*)(data==0?"null":data), rc);
    if (!rc) {
        fprintf(stderr, "\tname = %s\n", name);
    }
    if(batchMode)
      shutdownThisThing=1;
}

void my_data_completion(int rc, const char *value, int value_len,
        const struct Stat *stat, const void *data) {
    struct timeval tv;
    int sec;
    int usec;
    gettimeofday(&tv, 0);
    sec = tv.tv_sec - startTime.tv_sec;
    usec = tv.tv_usec - startTime.tv_usec;
    fprintf(stderr, "time = %d msec\n", sec*1000 + usec/1000);
    fprintf(stderr, "%s: rc = %d\n", (char*)data, rc);
    if (value) {
        fprintf(stderr, " value_len = %d\n", value_len);
        assert(write(2, value, value_len) == value_len);
    }
    fprintf(stderr, "\nStat:\n");
    dumpStat(stat);
    free((void*)data);
    if(batchMode)
      shutdownThisThing=1;
}

void my_silent_data_completion(int rc, const char *value, int value_len,
        const struct Stat *stat, const void *data) {
    recvd++;
    fprintf(stderr, "Data completion %s rc = %d\n",(char*)data,rc);
    free((void*)data);
    if (recvd==to_send) {
        fprintf(stderr,"Recvd %d responses for %d requests sent\n",recvd,to_send);
        if(batchMode)
          shutdownThisThing=1;
    }
}

void my_strings_completion(int rc, const struct String_vector *strings,
        const void *data) {
    struct timeval tv;
    int sec;
    int usec;
    int i;

    gettimeofday(&tv, 0);
    sec = tv.tv_sec - startTime.tv_sec;
    usec = tv.tv_usec - startTime.tv_usec;
    fprintf(stderr, "time = %d msec\n", sec*1000 + usec/1000);
    fprintf(stderr, "%s: rc = %d\n", (char*)data, rc);
    if (strings)
        for (i=0; i < strings->count; i++) {
            fprintf(stderr, "\t%s\n", strings->data[i]);
        }
    free((void*)data);
    gettimeofday(&tv, 0);
    sec = tv.tv_sec - startTime.tv_sec;
    usec = tv.tv_usec - startTime.tv_usec;
    fprintf(stderr, "time = %d msec\n", sec*1000 + usec/1000);
    if(batchMode)
      shutdownThisThing=1;
}

void my_void_completion(int rc, const void *data) {
    fprintf(stderr, "%s: rc = %d\n", (char*)data, rc);
    free((void*)data);
    if(batchMode)
      shutdownThisThing=1;
}

void my_stat_completion(int rc, const struct Stat *stat, const void *data) {
    fprintf(stderr, "%s: rc = %d Stat:\n", (char*)data, rc);
    dumpStat(stat);
    free((void*)data);
    if(batchMode)
      shutdownThisThing=1;
}

void my_silent_stat_completion(int rc, const struct Stat *stat,
        const void *data) {
    //    fprintf(stderr, "State completion: [%s] rc = %d\n", (char*)data, rc);
    sent++;
    free((void*)data);
}

static void sendRequest(const char* data) {
    zoo_aset(zh, "/od", data, strlen(data), -1, my_silent_stat_completion,
            strdup("/od"));
    zoo_aget(zh, "/od", 1, my_silent_data_completion, strdup("/od"));
}

void od_completion(int rc, const struct Stat *stat, const void *data) {
    int i;
    fprintf(stderr, "od command response: rc = %d Stat:\n", rc);
    dumpStat(stat);
    // send a whole bunch of requests
    recvd=0;
    sent=0;
    to_send=200;
    for (i=0; i<to_send; i++) {
        char buf[4096*16];
        memset(buf, -1, sizeof(buf)-1);
        buf[sizeof(buf)-1]=0;
        sendRequest(buf);
    }
}

int startsWith(const char *line, const char *prefix) {
    int len = strlen(prefix);
    return strncmp(line, prefix, len) == 0;
}

static const char *hostPort;

void processline(char *line) {
    int rc;
    int async = (line[0] == 'a');
    if (async) {
        line++;
    }
    if (startsWith(line, "get ")) {
        line += 4;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
        gettimeofday(&startTime, 0);
        rc = zoo_aget(zh, line, 1, my_data_completion, strdup(line));
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "set ")) {
        char *ptr;
        line += 4;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
        ptr = strchr(line, ' ');
        if (!ptr) {
            fprintf(stderr, "No data found after path\n");
            return;
        }
        *ptr = '\0';
        ptr++;
        if (async) {
            rc = zoo_aset(zh, line, ptr, strlen(ptr), -1, my_stat_completion,
                    strdup(line));
        } else {
            rc = zoo_set(zh, line, ptr, strlen(ptr), -1);
        }
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "ls ")) {
        line += 3;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
        gettimeofday(&startTime, 0);
        rc= zoo_aget_children(zh, line, 1, my_strings_completion, strdup(line));
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "create ")) {
        int flags = 0;
        line += 7;
        if (line[0] == '+') {
            line++;
            if (line[0] == 'e') {
                flags |= ZOO_EPHEMERAL;
                line++;
            }
            if (line[0] == 's') {
                flags |= ZOO_SEQUENCE;
                line++;
            }
            line++;
        }
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
        fprintf(stderr, "Creating [%s] node\n", line);
//        {
//            struct ACL _CREATE_ONLY_ACL_ACL[] = {{ZOO_PERM_CREATE, ZOO_ANYONE_ID_UNSAFE}};
//            struct ACL_vector CREATE_ONLY_ACL = {1,_CREATE_ONLY_ACL_ACL};
//            rc = zoo_acreate(zh, line, "new", 3, &CREATE_ONLY_ACL, flags,
//                    my_string_completion, strdup(line));
//        }
        rc = zoo_acreate(zh, line, "new", 3, &ZOO_OPEN_ACL_UNSAFE, flags,
                my_string_completion, strdup(line));
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "delete ")) {
        line += 7;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
        if (async) {
            rc = zoo_adelete(zh, line, -1, my_void_completion, strdup(line));
        } else {
            rc = zoo_delete(zh, line, -1);
        }
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "sync ")) {
        line += 5;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
        rc = zoo_async(zh, line, my_string_completion, strdup(line));
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (startsWith(line, "exists ")) {
        line += 7;
        if (line[0] != '/') {
            fprintf(stderr, "Path must start with /, found: %s\n", line);
            return;
        }
#ifndef THREADED
        rc = zoo_aexists(zh, line, 1, my_stat_completion, strdup(line));
#else
        struct Stat stat;
        rc = zoo_exists(zh, line, 1, &stat);
#endif
        if (rc) {
            fprintf(stderr, "Error %d for %s\n", rc, line);
        }
    } else if (strcmp(line, "myid") == 0) {
        printf("session Id = %llx\n", _LL_CAST_ zoo_client_id(zh)->client_id);
    } else if (strcmp(line, "reinit") == 0) {
        zookeeper_close(zh);
        // we can't send myid to the server here -- zookeeper_close() removes 
        // the session on the server. We must start anew.
        zh = zookeeper_init(hostPort, watcher, 10000, 0, 0, 0);
    } else if (startsWith(line, "quit")) {
        fprintf(stderr, "Quitting...\n");
        shutdownThisThing=1;
    } else if (startsWith(line, "od")) {
        const char val[]="fire off";
        fprintf(stderr, "Overdosing...\n");
        rc = zoo_aset(zh, "/od", val, sizeof(val)-1, -1, od_completion, 0);
        if (rc)
            fprintf(stderr, "od command failed: %d\n", rc);
    }
}

int main(int argc, char **argv) {
#ifndef THREADED
    fd_set rfds, wfds, efds;
    int processed=0;
#endif
    char buffer[4096];
    char p[2048];
#ifdef YCA  
    char *cert=0;
    char appId[64];
#endif
    int bufoff = 0;
    FILE *fh;

    if (argc < 2) {
        fprintf(stderr,
                "USAGE %s zookeeper_host_list [clientid_file|cmd:(ls|create|od|...)]\n", 
                argv[0]);
        return 2;
    }
    if (argc > 2) {
      if(strncmp("cmd:",argv[2],4)==0){
        strcpy(cmd,argv[2]+4);
        batchMode=1;
        fprintf(stderr,"Batch mode: %s\n",cmd);
      }else{
        clientIdFile = argv[2];
        fh = fopen(clientIdFile, "r");
        if (fh) {
            if (fread(&myid, sizeof(myid), 1, fh) != sizeof(myid)) {
                memset(&myid, 0, sizeof(myid));
            }
            fclose(fh);
        }
      }
    }
#ifdef YCA
    strcpy(appId,"yahoo.example.yca_test");
    cert = yca_get_cert_once(appId);
    if(cert!=0) {
        fprintf(stderr,"Certificate for appid [%s] is [%s]\n",appId,cert);
        strncpy(p,cert,sizeof(p)-1);
        free(cert);
    } else {
        fprintf(stderr,"Certificate for appid [%s] not found\n",appId);
        strcpy(p,"dummy");
    }
#else
    strcpy(p, "dummy");
#endif
    zoo_set_debug_level(ZOO_LOG_LEVEL_DEBUG);
    zoo_deterministic_conn_order(1); // enable deterministic order
    hostPort = argv[1];
    zh = zookeeper_init(hostPort, watcher, 10000, &myid, 0, 0);
    if (!zh) {
        return errno;
    }

#ifdef YCA
    if(zoo_add_auth(zh,"yca",p,strlen(p),0,0)!=ZOK)
    return 2;
#endif

#ifdef THREADED
    while(!shutdownThisThing) {
        int rc;
        int len = sizeof(buffer) - bufoff -1;
        if (len <= 0) {
            fprintf(stderr, "Can't handle lines that long!\n");
            exit(2);
        }
        rc = read(0, buffer+bufoff, len);
        if (rc <= 0) {
            fprintf(stderr, "bye\n");
            shutdownThisThing=1;
            break;
        }
        bufoff += rc;
        buffer[bufoff] = '\0';
        while (strchr(buffer, '\n')) {
            char *ptr = strchr(buffer, '\n');
            *ptr = '\0';
            processline(buffer);
            ptr++;
            memmove(buffer, ptr, strlen(ptr)+1);
            bufoff = 0;
        }
    }
#else 
    FD_ZERO(&rfds);
    FD_ZERO(&wfds);
    FD_ZERO(&efds);
    while (!shutdownThisThing) {
        int fd;
        int interest;
        int events;
        struct timeval tv;
        int rc;
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
        rc = select(fd+1, &rfds, &wfds, &efds, &tv);
        events = 0;
        if (FD_ISSET(fd, &rfds)) {
            events |= ZOOKEEPER_READ;
        }
        if (FD_ISSET(fd, &wfds)) {
            events |= ZOOKEEPER_WRITE;
        }
        if(batchMode && processed==0){
          //batch mode
          processline(cmd);
          processed=1;
        }
        if (FD_ISSET(0, &rfds)) {
            int rc;
            int len = sizeof(buffer) - bufoff -1;
            if (len <= 0) {
                fprintf(stderr, "Can't handle lines that long!\n");
                exit(2);
            }
            rc = read(0, buffer+bufoff, len);
            if (rc <= 0) {
                fprintf(stderr, "bye\n");
                break;
            }
            bufoff += rc;
            buffer[bufoff] = '\0';
            while (strchr(buffer, '\n')) {
                char *ptr = strchr(buffer, '\n');
                *ptr = '\0';
                processline(buffer);
                ptr++;
                memmove(buffer, ptr, strlen(ptr)+1);
                bufoff = 0;
            }
        }
        zookeeper_process(zh, events);
    }
#endif
    if (to_send!=0)
        fprintf(stderr,"Recvd %d responses for %d requests sent\n",recvd,sent);
    zookeeper_close(zh);
    return 0;
}
