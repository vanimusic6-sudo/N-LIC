#define _POSIX_C_SOURCE 200809L
#include "nlic_udp.h"
extern int nlic_v2_stopping(void);
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <poll.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#define MAX_UDP_DATAGRAM 65535
#define MAX_KNOWN_TARGETS 8
#define DNS_CACHE_SLOTS 8
#define DNS_CACHE_TTL_SECONDS 45
typedef struct {
    char host[256];
    unsigned port;
    struct sockaddr_storage addr;
    socklen_t len;
    long expires;
} udp_cache_entry;
static int sender_is_local(const struct sockaddr_storage *source) {
    if(source->ss_family!=AF_INET)return 0;
    const struct sockaddr_in *a=(const struct sockaddr_in*)source;
    return a->sin_addr.s_addr==htonl(INADDR_LOOPBACK);
}
static int same_sender(const struct sockaddr_storage *a,const struct sockaddr_storage *b) {
    if(a->ss_family!=b->ss_family)return 0;
    if(a->ss_family==AF_INET) {
        const struct sockaddr_in *x=(const struct sockaddr_in*)a;
        const struct sockaddr_in *y=(const struct sockaddr_in*)b;
        return x->sin_port==y->sin_port && x->sin_addr.s_addr==y->sin_addr.s_addr;
    }
    return 0;
}
static int same_remote(const struct sockaddr_storage *a,const struct sockaddr_storage *b) {
    if(a->ss_family!=b->ss_family)return 0;
    if(a->ss_family==AF_INET) {
        const struct sockaddr_in *x=(const struct sockaddr_in*)a;
        const struct sockaddr_in *y=(const struct sockaddr_in*)b;
        return x->sin_port==y->sin_port && x->sin_addr.s_addr==y->sin_addr.s_addr;
    }
    if(a->ss_family==AF_INET6) {
        const struct sockaddr_in6 *x=(const struct sockaddr_in6*)a;
        const struct sockaddr_in6 *y=(const struct sockaddr_in6*)b;
        return x->sin6_port==y->sin6_port && !memcmp(&x->sin6_addr,&y->sin6_addr,16);
    }
    return 0;
}
static long monotonic_seconds(void) {
    struct timespec ts;
    return clock_gettime(CLOCK_MONOTONIC,&ts)==0?(long)ts.tv_sec:0;
}
static int parse_packet(const uint8_t *packet,size_t n,
                        struct sockaddr_storage *destination,socklen_t *destlen,
                        size_t *data_offset,udp_cache_entry cache[DNS_CACHE_SLOTS],
                        unsigned *next_cache) {
    if(!packet||n<10||packet[0]||packet[1]||packet[2])return -1;
    size_t i=4;char host[256];
    if(packet[3]==1) {
        if(i+4+2>n)return -1;
        if(!inet_ntop(AF_INET,packet+i,host,sizeof(host)))return -1;
        i+=4;
    }else if(packet[3]==4) {
        if(i+16+2>n)return -1;
        if(!inet_ntop(AF_INET6,packet+i,host,sizeof(host)))return -1;
        i+=16;
    }else if(packet[3]==3) {
        if(i>=n)return -1;
        size_t length=packet[i++];
        if(length==0||length>253||i+length+2>n)return -1;
        for(size_t j=0;j<length;j++) {
            uint8_t c=packet[i+j];
            if(c<0x21||c>0x7e||c=='/'||c=='\\'||c=='%')return -1;
        }
        memcpy(host,packet+i,length);host[length]=0;i+=length;
    }else return -1;
    unsigned port=((unsigned)packet[i]<<8)|packet[i+1];i+=2;
    if(port==0||i>=n)return -1;
    const long now=monotonic_seconds();
    for(unsigned k=0;k<DNS_CACHE_SLOTS;k++) {
        udp_cache_entry *e=&cache[k];
        if(e->expires>now&&e->port==port&&!strcmp(e->host,host)) {
            *destination=e->addr;*destlen=e->len;*data_offset=i;return 0;
        }
    }
    char portbuf[8];(void)snprintf(portbuf,sizeof(portbuf),"%u",port);
    struct addrinfo hints={0},*addresses=NULL;
    hints.ai_socktype=SOCK_DGRAM;hints.ai_family=AF_UNSPEC;
    if(getaddrinfo(host,portbuf,&hints,&addresses)!=0||!addresses)return -1;
    int rc=-1;struct addrinfo *chosen=NULL;
    for(struct addrinfo *e=addresses;e;e=e->ai_next) {
        if((e->ai_family!=AF_INET&&e->ai_family!=AF_INET6)||
            e->ai_addrlen>sizeof(*destination))continue;
        if(!chosen||(e->ai_family==AF_INET&&chosen->ai_family!=AF_INET))
            chosen=e;
    }
    if(chosen) {
        memset(destination,0,sizeof(*destination));
        memcpy(destination,chosen->ai_addr,chosen->ai_addrlen);
        *destlen=(socklen_t)chosen->ai_addrlen;*data_offset=i;
        udp_cache_entry *e=&cache[*next_cache%DNS_CACHE_SLOTS];
        memset(e,0,sizeof(*e));memcpy(e->host,host,strlen(host)+1);
        e->port=port;e->addr=*destination;e->len=*destlen;
        e->expires=now+DNS_CACHE_TTL_SECONDS;(*next_cache)++;rc=0;
    }
    freeaddrinfo(addresses);
    return rc;
}
int nlic_udp_associate(int client,unsigned idle_seconds) {
    int relay=socket(AF_INET,SOCK_DGRAM,0);
    if(relay<0)return -1;
    struct sockaddr_in bindaddr={0};bindaddr.sin_family=AF_INET;
    bindaddr.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    if(bind(relay,(struct sockaddr*)&bindaddr,sizeof(bindaddr))) {
        close(relay);return -1;
    }
    socklen_t blen=sizeof(bindaddr);
    if(getsockname(relay,(struct sockaddr*)&bindaddr,&blen)) {
        close(relay);return -1;
    }
    const uint16_t p=ntohs(bindaddr.sin_port);
    uint8_t reply[10]={5,0,0,1,127,0,0,1,(uint8_t)(p>>8),(uint8_t)p};
    if(send(client,reply,sizeof(reply),MSG_NOSIGNAL)!=(ssize_t)sizeof(reply)) {
        close(relay);return -1;
    }
    int outgoing4=socket(AF_INET,SOCK_DGRAM,0);
    int outgoing6=socket(AF_INET6,SOCK_DGRAM,0);
    if(outgoing4<0&&outgoing6<0){close(relay);return -1;}
    uint8_t *packet=malloc(MAX_UDP_DATAGRAM),*response=malloc(MAX_UDP_DATAGRAM);
    if(!packet||!response) {
        free(packet);free(response);close(relay);
        if(outgoing4>=0)close(outgoing4);
        if(outgoing6>=0)close(outgoing6);
        return -1;
    }
    struct sockaddr_storage sender={0},known[MAX_KNOWN_TARGETS];
    udp_cache_entry dns_cache[DNS_CACHE_SLOTS]={0};
    unsigned next_cache=0;socklen_t senderlen=0;size_t known_count=0,next=0;
    long last=monotonic_seconds();int rc=0;
    while(!nlic_v2_stopping()) {
        struct pollfd fd[4]={{client,POLLIN,0},{relay,POLLIN,0},
                             {outgoing4,POLLIN,0},{outgoing6,POLLIN,0}};
        int nfd=2;
        if(outgoing4>=0)nfd++;
        if(outgoing6>=0)nfd++;
        if(outgoing4<0){fd[2]=fd[3];fd[3].fd=-1;}
        int n=poll(fd,(nfds_t)nfd,500);
        if(n<0){if(errno==EINTR)continue;rc=-1;break;}
        if(n==0){if(monotonic_seconds()-last>(long)idle_seconds)break;continue;}
        if(fd[0].revents&(POLLERR|POLLHUP|POLLIN)) {
            uint8_t tmp[1];ssize_t got=recv(client,tmp,sizeof(tmp),MSG_DONTWAIT);
            if(got<=0)break;
        }
        if(fd[1].revents&POLLIN) {
            struct sockaddr_storage from={0};socklen_t flen=sizeof(from);
            ssize_t count=recvfrom(relay,packet,MAX_UDP_DATAGRAM,0,
                                   (struct sockaddr*)&from,&flen);
            if(count>0&&sender_is_local(&from)&&
                (!senderlen||same_sender(&sender,&from))) {
                if(!senderlen){sender=from;senderlen=flen;}
                struct sockaddr_storage dst={0};socklen_t dlen=0;size_t off=0;
                if(!parse_packet(packet,(size_t)count,&dst,&dlen,&off,
                                 dns_cache,&next_cache)) {
                    int remote=dst.ss_family==AF_INET?outgoing4:outgoing6;
                    if(remote>=0) {
                        if(sendto(remote,packet+off,(size_t)count-off,MSG_NOSIGNAL,
                                  (struct sockaddr*)&dst,dlen)>=0) {
                            known[next]=dst;
                            if(known_count<MAX_KNOWN_TARGETS)known_count++;
                            next=(next+1)%MAX_KNOWN_TARGETS;last=monotonic_seconds();
                        }
                    }
                }
            }
        }
        for(int i=2;i<nfd;i++) {
            if(!(fd[i].revents&POLLIN)||!senderlen)continue;
            struct sockaddr_storage remote={0};socklen_t rlen=sizeof(remote);
            ssize_t got=recvfrom(fd[i].fd,packet,MAX_UDP_DATAGRAM-22,0,
                                 (struct sockaddr*)&remote,&rlen);
            if(got<=0)continue;
            int registered=0;
            for(size_t k=0;k<known_count;k++)registered|=same_remote(&remote,&known[k]);
            if(!registered)continue;
            size_t off=0;response[off++]=0;response[off++]=0;response[off++]=0;
            if(remote.ss_family==AF_INET) {
                const struct sockaddr_in *a=(const struct sockaddr_in*)&remote;
                response[off++]=1;memcpy(response+off,&a->sin_addr,4);off+=4;
                memcpy(response+off,&a->sin_port,2);off+=2;
            }else if(remote.ss_family==AF_INET6) {
                const struct sockaddr_in6 *a=(const struct sockaddr_in6*)&remote;
                response[off++]=4;memcpy(response+off,&a->sin6_addr,16);off+=16;
                memcpy(response+off,&a->sin6_port,2);off+=2;
            }else continue;
            memcpy(response+off,packet,(size_t)got);off+=(size_t)got;
            (void)sendto(relay,response,off,MSG_NOSIGNAL,
                         (struct sockaddr*)&sender,senderlen);
            last=monotonic_seconds();
        }
    }
    memset(response,0,MAX_UDP_DATAGRAM);
    memset(dns_cache,0,sizeof(dns_cache));
    free(packet);free(response);close(relay);
    if(outgoing4>=0)close(outgoing4);
    if(outgoing6>=0)close(outgoing6);
    return rc;
}
