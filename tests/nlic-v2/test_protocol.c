#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "nlic_protocol.h"

static uint32_t rng=0x4e4c4943u;
static uint32_t rand32(void) {
    rng ^= rng<<13; rng ^= rng>>17; rng ^= rng<<5; return rng;
}
int main(void) {
    uint8_t hello[]={0x16,0x03,0x03,0,6,1,0,0,2,0xab,0xcd};
    uint8_t output[32]={0};
    assert(nlic_detect(hello,sizeof(hello),0)==NLIC_FLOW_TLS);
    assert(nlic_tls_split_record(hello,sizeof(hello),output,sizeof(output),3)
        ==sizeof(hello)+5);
    assert(output[3]==0&&output[4]==3);
    assert(output[11]==0x16&&output[12]==3&&output[13]==3);
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
        (void)nlic_tls_split_record(corpus,length,output,sizeof(output),5);
    }
    puts("N-LIC native protocol fuzz regression OK");
    return 0;
}
