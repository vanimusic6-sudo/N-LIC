#define _POSIX_C_SOURCE 200809L
#include "nlic_socks.h"
#include "nlic_protocol.h"
#include "nlic_udp.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define READBUF 65536
#define NLIC_MAX_THREADS 64
static pthread_mutex_t g_mutex=PTHREAD_MUTEX_INITIALIZER;
static int g_listener=-1;
static unsigned g_active=0;
static int g_running=0;
static atomic_int g_stop=0;
int nlic_v2_stopping(void) { return atomic_load(&g_stop); }
static void deadline_socket(int fd,int seconds) {
    struct timeval tv={seconds,0};
    (void)setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&tv,sizeof(tv));
    (void)setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&tv,sizeof(tv));
}
static int recv_full(int fd,void *buffer,size_t count) {
    uint8_t *p=(uint8_t*)buffer;
    while(count) {
        ssize_t n=recv(fd,p,count,0);
        if(n==0)return -1;
        if(n<0){if(errno==EINTR)continue;return -1;}
        p+=(size_t)n;count-=(size_t)n;
    }
    return 0;
}
static int send_full(int fd,const void *buffer,size_t count) {
    const uint8_t *p=(const uint8_t*)buffer;
    while(count && !g_stop) {
        ssize_t n=send(fd,p,count,MSG_NOSIGNAL);
        if(n<=0){if(n<0 && errno==EINTR)continue;return -1;}
        p+=(size_t)n;count-=(size_t)n;
    }
    return count?-1:0;
}
static int secure_equal(const char *expected,const uint8_t *actual,size_t size) {
    const size_t n=strlen(expected);
    unsigned diff=(unsigned)(n^size);
    for(size_t i=0;i<size;i++)
        diff|=(unsigned)(actual[i]^((i<n)?(uint8_t)expected[i]:0));
    return diff==0;
}
static int socks_authenticate(int fd,const nlic_config *cfg) {
    uint8_t head[2];
    if(recv_full(fd,head,2)||head[0]!=5||head[1]==0||head[1]>16)return -1;
    uint8_t methods[16];
    if(recv_full(fd,methods,head[1]))return -1;
    int supports_password=0;
    for(int i=0;i<head[1];i++)supports_password|=(methods[i]==2);
    const uint8_t reply[]={5,supports_password?2:255};
    if(send_full(fd,reply,2)||!supports_password)return -1;
    uint8_t auth[2];
    if(recv_full(fd,auth,2)||auth[0]!=1||auth[1]==0||auth[1]>64)return -1;
    uint8_t username[64];
    if(recv_full(fd,username,auth[1]))return -1;
    uint8_t plen;
    if(recv_full(fd,&plen,1)||plen==0||plen>128)return -1;
    uint8_t password[128];
    if(recv_full(fd,password,plen))return -1;
    int ok=secure_equal(cfg->username,username,auth[1]) &&
           secure_equal(cfg->password,password,plen);
    memset(username,0,sizeof(username));
    memset(password,0,sizeof(password));
    const uint8_t ack[]={1,ok?0:1};
    if(send_full(fd,ack,2))return -1;
    return ok?0:-1;
}
static int socks_reply(int fd,uint8_t status) {
    const uint8_t resp[]={5,status,0,1,0,0,0,0,0,0};
    return send_full(fd,resp,sizeof(resp));
}
static int dial_with_timeout(int fd,const struct sockaddr *dst,
                             socklen_t dstlen,int timeout_ms) {
    int flags=fcntl(fd,F_GETFL,0);
    if(flags<0||fcntl(fd,F_SETFL,flags|O_NONBLOCK)<0)return -1;
    int rc=connect(fd,dst,dstlen);
    if(rc<0&&errno==EINPROGRESS) {
        struct pollfd pfd={fd,POLLOUT,0};
        do{rc=poll(&pfd,1,timeout_ms);}while(rc<0&&errno==EINTR);
        if(rc>0) {
            int soerr=0;socklen_t len=sizeof(soerr);
            if(getsockopt(fd,SOL_SOCKET,SO_ERROR,&soerr,&len)<0||soerr)rc=-1;
            else rc=0;
        }else rc=-1;
    }
    (void)fcntl(fd,F_SETFL,flags);
    return rc==0?0:-1;
}
static int connect_target(int sock,char *host,size_t cap,char *port,size_t pcap) {
    uint8_t req[4];
    if(recv_full(sock,req,4)||req[0]!=5||req[2]!=0)return -1;
    if(req[1]!=1&&req[1]!=3){(void)socks_reply(sock,7);return -1;}
    if(req[3]==1||req[3]==4) {
        uint8_t raw[16];size_t n=req[3]==1?4:16;
        if(recv_full(sock,raw,n))return -1;
        if(!inet_ntop(req[3]==1?AF_INET:AF_INET6,raw,host,(socklen_t)cap))return -1;
    }else if(req[3]==3) {
        uint8_t n;
        if(recv_full(sock,&n,1)||n==0||n>253||(size_t)n>=cap)return -1;
        uint8_t raw[253];
        if(recv_full(sock,raw,n))return -1;
        for(size_t i=0;i<n;i++) {
            if(raw[i]<0x21||raw[i]>0x7e||raw[i]=='/'||raw[i]=='\\'||raw[i]=='%')return -1;
        }
        memcpy(host,raw,n);host[n]='\0';
    }else{(void)socks_reply(sock,8);return -1;}
    uint8_t pb[2];
    if(recv_full(sock,pb,2))return -1;
    unsigned nport=((unsigned)pb[0]<<8)|pb[1];
    if(nport==0&&req[1]==1){(void)socks_reply(sock,1);return -1;}
    if(req[1]==3)return -3;
    (void)snprintf(port,pcap,"%u",nport);
    struct addrinfo hints={0},*infos=NULL;
    hints.ai_socktype=SOCK_STREAM;hints.ai_family=AF_UNSPEC;
    int rc=getaddrinfo(host,port,&hints,&infos);
    if(rc!=0||!infos){(void)socks_reply(sock,4);return -1;}
    int upstream=-1;
    for(struct addrinfo *it=infos;it&&!g_stop;it=it->ai_next) {
        int fd=socket(it->ai_family,it->ai_socktype,it->ai_protocol);
        if(fd<0)continue;
        deadline_socket(fd,10);
        if(dial_with_timeout(fd,it->ai_addr,it->ai_addrlen,6000)==0){upstream=fd;break;}
        close(fd);
    }
    freeaddrinfo(infos);
    if(upstream<0){(void)socks_reply(sock,5);return -1;}
    if(socks_reply(sock,0)){close(upstream);return -1;}
    return upstream;
}
static int forward_first(int sock,int upstream,const nlic_config *cfg,
                         uint8_t *buffer,uint8_t *scratch) {
    ssize_t n=recv(sock,buffer,READBUF-5,0);
    if(n<1)return -1;
    size_t size=(size_t)n;
    if(!cfg->tls_record_split || nlic_detect(buffer,size,0)!=NLIC_FLOW_TLS)
        return send_full(upstream,buffer,size);
    if(size>=5) {
        size_t record=(size_t)(((unsigned)buffer[3]<<8)|buffer[4])+5;
        if(record>0&&record<=READBUF-5) {
            while(size<record&&!g_stop) {
                struct pollfd pfd={sock,POLLIN,0};
                if(poll(&pfd,1,2000)!=1)break;
                n=recv(sock,buffer+size,record-size,0);
                if(n<=0)break;
                size+=(size_t)n;
            }
            if(size>=record) {
                size_t offset=nlic_tls_sni_midpoint(buffer,size);
                if(offset==0&&record>20)offset=record/2-5;
                const size_t transformed=nlic_tls_split_record(
                    buffer,size,scratch,READBUF,offset);
                if(transformed)return send_full(upstream,scratch,transformed);
            }
        }
    }
    return send_full(upstream,buffer,size);
}
static long monotonic_s(void) {
    struct timespec tp;
    if(clock_gettime(CLOCK_MONOTONIC,&tp))return 0;
    return (long)tp.tv_sec;
}
static void relay(int client,int upstream,const nlic_config *cfg) {
    uint8_t *buf=malloc(READBUF),*tmp=malloc(READBUF);
    if(!buf||!tmp)goto done;
    deadline_socket(client,15);deadline_socket(upstream,15);
    if(forward_first(client,upstream,cfg,buf,tmp))goto done;
    int copen=1,uopen=1;long last=monotonic_s();
    while((copen||uopen)&&!g_stop) {
        struct pollfd pfds[2]={{client,copen?POLLIN:0,0},
                                {upstream,uopen?POLLIN:0,0}};
        int n=poll(pfds,2,1000);
        if(n<0){if(errno==EINTR)continue;break;}
        if(!n){if(monotonic_s()-last>(long)cfg->idle_seconds)break;continue;}
        for(int i=0;i<2;i++) {
            if(!(pfds[i].revents&(POLLIN|POLLHUP|POLLERR)))continue;
            int in=i?upstream:client,out=i?client:upstream;
            ssize_t size=recv(in,buf,READBUF,0);
            if(size<=0){
                if(!i)copen=0;else uopen=0;
                (void)shutdown(out,SHUT_WR);
            }else if(send_full(out,buf,(size_t)size)) {
                copen=0;uopen=0;break;
            }else last=monotonic_s();
        }
    }
done:
    free(buf);free(tmp);
}
struct worker{int client;nlic_config cfg;};
static void *worker_main(void *v) {
    struct worker *w=(struct worker*)v;
    int client=w->client;nlic_config cfg=w->cfg;free(w);
    deadline_socket(client,10);
    if(!g_stop&&!socks_authenticate(client,&cfg)) {
        char host[256],port[8];
        int upstream=connect_target(client,host,sizeof(host),port,sizeof(port));
        if(upstream==-3){(void)nlic_udp_associate(client,cfg.idle_seconds);}
        else if(upstream>=0) {
            relay(client,upstream,&cfg);
            (void)shutdown(upstream,SHUT_RDWR);close(upstream);
        }
    }
    (void)shutdown(client,SHUT_RDWR);close(client);
    memset(&cfg,0,sizeof(cfg));
    pthread_mutex_lock(&g_mutex);
    if(g_active>0)g_active--;
    pthread_mutex_unlock(&g_mutex);
    return NULL;
}
int nlic_v2_run(const nlic_config *requested) {
    if(!requested||requested->listen_port==0||
       requested->max_connections==0||requested->max_connections>NLIC_MAX_THREADS||
       requested->idle_seconds<10||requested->idle_seconds>900||
       !memchr(requested->username,0,sizeof(requested->username))||
       !memchr(requested->password,0,sizeof(requested->password))||
       strlen(requested->username)<3||strlen(requested->password)<24)return -1;
    pthread_mutex_lock(&g_mutex);
    if(g_running||g_active>0){pthread_mutex_unlock(&g_mutex);return -1;}
    g_stop=0;g_running=1;pthread_mutex_unlock(&g_mutex);
    int outcome=-1;
    int listener=socket(AF_INET,SOCK_STREAM,0);
    if(listener<0)goto failure;
    int yes=1;
    (void)setsockopt(listener,SOL_SOCKET,SO_REUSEADDR,&yes,sizeof(yes));
    struct sockaddr_in addr={0};addr.sin_family=AF_INET;
    addr.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    addr.sin_port=htons(requested->listen_port);
    if(bind(listener,(struct sockaddr*)&addr,sizeof(addr))||listen(listener,64)){
        close(listener);goto failure;
    }
    pthread_mutex_lock(&g_mutex);g_listener=listener;pthread_mutex_unlock(&g_mutex);
    while(!g_stop) {
        struct pollfd pfd={listener,POLLIN,0};
        int ready=poll(&pfd,1,500);
        if(ready<=0)continue;
        int fd=accept(listener,NULL,NULL);
        if(fd<0){if(g_stop||errno==EBADF)break;continue;}
        pthread_mutex_lock(&g_mutex);
        if(g_active>=requested->max_connections){
            pthread_mutex_unlock(&g_mutex);close(fd);continue;
        }
        g_active++;pthread_mutex_unlock(&g_mutex);
        struct worker *w=calloc(1,sizeof(*w));
        if(!w){close(fd);pthread_mutex_lock(&g_mutex);g_active--;pthread_mutex_unlock(&g_mutex);continue;}
        w->client=fd;w->cfg=*requested;
        pthread_t thread;
        if(pthread_create(&thread,NULL,worker_main,w)){
            free(w);close(fd);
            pthread_mutex_lock(&g_mutex);g_active--;pthread_mutex_unlock(&g_mutex);
        }else pthread_detach(thread);
    }
    pthread_mutex_lock(&g_mutex);
    if(g_listener==listener)g_listener=-1;
    pthread_mutex_unlock(&g_mutex);
    close(listener);outcome=0;
failure:
    pthread_mutex_lock(&g_mutex);g_running=0;pthread_mutex_unlock(&g_mutex);
    return outcome;
}
void nlic_v2_stop(void) {
    pthread_mutex_lock(&g_mutex);g_stop=1;
    if(g_listener>=0)(void)shutdown(g_listener,SHUT_RDWR);
    pthread_mutex_unlock(&g_mutex);
}
#ifdef NLIC_STANDALONE
int main(int argc,char **argv) {
    if(argc!=4)return 1;
    nlic_config cfg={0};
    cfg.listen_port=(uint16_t)strtoul(argv[1],NULL,10);
    (void)snprintf(cfg.username,sizeof(cfg.username),"%s",argv[2]);
    (void)snprintf(cfg.password,sizeof(cfg.password),"%s",argv[3]);
    cfg.tls_record_split=1;cfg.max_connections=32;cfg.idle_seconds=120;
    return nlic_v2_run(&cfg);
}
#endif
