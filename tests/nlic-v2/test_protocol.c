#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "nlic_protocol.h"

static uint32_t rng=0x4e4c4943u;
static uint32_t rand32(void) {
    rng ^= rng<<13; rng ^= rng>>17; rng ^= rng<<5; return rng;
}
static void test_complete_sni_triple_split(void) {
    uint8_t hello[256]={0},parts[272]={0},restored[256]={0};
    size_t n=5;
    hello[0]=0x16;hello[1]=0x03;hello[2]=0x03;
    hello[n++]=1;size_t hslen=n;n+=3;
    hello[n++]=3;hello[n++]=3;
    n+=32;                         /* random */
    hello[n++]=0;                  /* session id */
    hello[n++]=0;hello[n++]=2;     /* suites len */
    hello[n++]=0x13;hello[n++]=1;  /* TLS_AES_128_GCM_SHA256 */
    hello[n++]=1;hello[n++]=0;     /* compression */
    size_t extlen=n;n+=2;
    hello[n++]=0;hello[n++]=0;     /* SNI extension type */
    size_t ext_data=n;n+=2;
    size_t server_names=n;n+=2;
    hello[n++]=0;                  /* host_name */
    const char *host="example.com";
    const size_t hlen=strlen(host);
    hello[n++]=0;hello[n++]=(uint8_t)hlen;
    memcpy(hello+n,host,hlen);n+=hlen;
    size_t dlen=n-(server_names+2);
    hello[server_names]=(uint8_t)(dlen>>8);
    hello[server_names+1]=(uint8_t)dlen;
    dlen=n-(ext_data+2);
    hello[ext_data]=(uint8_t)(dlen>>8);
    hello[ext_data+1]=(uint8_t)dlen;
    dlen=n-(extlen+2);
    hello[extlen]=(uint8_t)(dlen>>8);
    hello[extlen+1]=(uint8_t)dlen;
    dlen=n-(hslen+3);
    hello[hslen]=(uint8_t)(dlen>>16);
    hello[hslen+1]=(uint8_t)(dlen>>8);
    hello[hslen+2]=(uint8_t)dlen;
    dlen=n-5;
    hello[3]=(uint8_t)(dlen>>8);
    hello[4]=(uint8_t)dlen;
    size_t start=0,end=0;
    assert(nlic_tls_sni_range(hello,n,&start,&end));
    assert(start>0&&end>start);
    const size_t produced=nlic_tls_split_sni_record(hello,n,parts,sizeof(parts));
    assert(produced==n+10);
    size_t input=0,output=0,records=0;
    while(input<produced) {
        assert(input+5<=produced);
        assert(parts[input]==0x16&&parts[input+1]==3);
        size_t len=(size_t)parts[input+3]*256+parts[input+4];
        assert(input+5+len<=produced);
        memcpy(restored+output,parts+input+5,len);
        output+=len;input+=5+len;records++;
    }
    assert(records==3&&output==n-5);
    assert(memcmp(restored,hello+5,n-5)==0);
    hello[0]=0x17; /* no transformation for non-handshake content */
    assert(!nlic_tls_split_sni_record(hello,n,parts,sizeof(parts)));
    /* Incomplete handshakes must remain untouched, never mutated. */
    hello[0]=0x16;
    assert(!nlic_tls_split_sni_record(hello,n-1,parts,sizeof(parts)));
}

int main(void) {
    test_complete_sni_triple_split();
    uint8_t hello[]={0x16,0x03,0x03,0,6,1,0,0,2,0xab,0xcd};
    uint8_t output[32]={0};
    assert(nlic_detect(hello,sizeof(hello),0)==NLIC_FLOW_TLS);
    assert(nlic_tls_split_record(hello,sizeof(hello),output,sizeof(output),3)
        ==sizeof(hello)+5);
    assert(output[3]==0&&output[4]==3);
    assert(output[8]==0x16&&output[9]==3&&output[10]==3);
    assert(memcmp(output+5,hello+5,3)==0);
    assert(memcmp(output+16-3,hello+8,3)==0);
    uint8_t quic1[]={0xc0,0,0,0,1,0,0,0};
    uint8_t quic2[]={0xd0,0x6b,0x33,0x43,0xcf,0,0,0};
    assert(nlic_detect(quic1,sizeof(quic1),1)==NLIC_FLOW_QUIC_INITIAL);
    assert(nlic_detect(quic2,sizeof(quic2),1)==NLIC_FLOW_QUIC_INITIAL);
    assert(nlic_detect((const uint8_t*)"GET / HTTP",10,0)==NLIC_FLOW_HTTP);
    assert(nlic_tls_sni_midpoint(hello,sizeof(hello))==0);
    uint8_t corpus[512];
    for(int i=0;i<10000;i++) {
        for(size_t k=0;k<sizeof(corpus);k++)corpus[k]=(uint8_t)rand32();
        size_t length=rand32()%sizeof(corpus);
        (void)nlic_detect(corpus,length,0);
        (void)nlic_detect(corpus,length,1);
        (void)nlic_tls_sni_midpoint(corpus,length);
        size_t left=0,right=0;
        (void)nlic_tls_sni_range(corpus,length,&left,&right);
        (void)nlic_tls_split_sni_record(corpus,length,output,sizeof(output));
        (void)nlic_tls_split_record(corpus,length,output,sizeof(output),5);
    }
    puts("N-LIC native protocol fuzz regression OK");
    return 0;
}
