#include "nlic_protocol.h"
#include <string.h>
static uint16_t u16(const uint8_t *p) { return ((uint16_t)p[0] << 8) | p[1]; }
nlic_flow_type nlic_detect(const uint8_t *p, size_t n, int udp) {
    if (!p || n < 1) return NLIC_FLOW_OTHER;
    if (udp) {
        /* QUIC v1/v2 Initial metadata only; never mutate encrypted UDP. */
        if (n >= 8 && (p[0] & 0xc0) == 0xc0) {
            uint32_t version=((uint32_t)p[1]<<24)|((uint32_t)p[2]<<16)|
                             ((uint32_t)p[3]<<8)|p[4];
            int initial=(version==0x00000001u && (p[0]&0x30)==0) ||
                        (version==0x6b3343cfu && (p[0]&0x30)==0x10);
            if (initial) {
                size_t dcid=p[5];
                if (dcid<=20 && 6+dcid<n) {
                    size_t scid=p[6+dcid];
                    if (scid<=20 && 7+dcid+scid<=n)
                        return NLIC_FLOW_QUIC_INITIAL;
                }
            }
        }
        return NLIC_FLOW_OTHER;
    }
    if (n>=9 && p[0]==0x16 && p[1]==0x03 && p[2]<=0x04 && p[5]==0x01)
        return NLIC_FLOW_TLS;
    if (n>=5 && (!memcmp(p,"GET ",4) || !memcmp(p,"POST ",5) ||
                 !memcmp(p,"HEAD ",5) || !memcmp(p,"PUT ",4)))
        return NLIC_FLOW_HTTP;
    return NLIC_FLOW_OTHER;
}
int nlic_tls_sni_range(const uint8_t *p,size_t n,
                       size_t *start,size_t *end) {
    if(!p||!start||!end||nlic_detect(p,n,0)!=NLIC_FLOW_TLS||n<9)return 0;
    size_t rlen=u16(p+3);
    if(rlen+5>n||rlen<42)return 0;
    size_t e=5+rlen;
    size_t hs=((size_t)p[6]<<16)|((size_t)p[7]<<8)|p[8];
    if(hs+4>rlen||hs<38)return 0;
    size_t i=9+2+32;
    if(i>=e)return 0;
    size_t sid=p[i++];
    if(i+sid+2>e)return 0;
    i+=sid;
    size_t suites=u16(p+i);i+=2;
    if(i+suites+1>e||suites%2)return 0;
    i+=suites;
    size_t compress=p[i++];
    if(i+compress+2>e)return 0;
    i+=compress;
    size_t extlen=u16(p+i);i+=2;
    if(i+extlen>e)return 0;
    const size_t ext_end=i+extlen;
    while(i+4<=ext_end) {
        const size_t kind=u16(p+i),len=u16(p+i+2);i+=4;
        if(i+len>ext_end)return 0;
        if(kind==0&&len>=5) {
            size_t j=i+2,list=u16(p+i);
            if(list>len-2||j+list>i+len)return 0;
            const size_t list_end=j+list;
            while(j+3<=list_end) {
                const size_t typ=p[j],hostlen=u16(p+j+1);j+=3;
                if(j+hostlen>list_end)return 0;
                if(typ==0&&hostlen>=6&&j>5&&j+hostlen<e) {
                    *start=j-5;
                    *end=j+hostlen-5;
                    return *start>1&&*end<rlen;
                }
                j+=hostlen;
            }
        }
        i+=len;
    }
    return 0;
}
size_t nlic_tls_sni_midpoint(const uint8_t *p,size_t n) {
    size_t start=0,end=0;
    return nlic_tls_sni_range(p,n,&start,&end)?
        start+(end-start)/2:0;
}
size_t nlic_tls_split_sni_record(const uint8_t *in,size_t in_len,
                                 uint8_t *out,size_t cap) {
    size_t start=0,end=0;
    if(!in||!out||in_len<10||in_len>cap||in_len>cap-10||
       !nlic_tls_sni_range(in,in_len,&start,&end))return 0;
    const size_t len=u16(in+3),mid=start+(end-start)/2;
    if(start<2||mid<=start||mid>=len)return 0;
    const size_t lengths[]={start,mid-start,len-mid};
    size_t offset=0;
    size_t output=0;
    for(size_t i=0;i<3;i++) {
        const size_t part=lengths[i];
        if(part==0||part>16384)return 0;
        memcpy(out+output,in,3);
        out[output+3]=(uint8_t)(part>>8);
        out[output+4]=(uint8_t)part;
        memcpy(out+output+5,in+5+offset,part);
        output+=5+part;
        offset+=part;
    }
    // Preserve any bytes following the first record unchanged.
    memcpy(out+output,in+5+len,in_len-5-len);
    return in_len+10;
}
size_t nlic_tls_split_record(const uint8_t *in,size_t in_len,
                            uint8_t *out,size_t cap,size_t pos) {
    if(!in||!out||in_len<10||pos<1||cap<5||in_len>cap-5 ||
       nlic_detect(in,in_len,0)!=NLIC_FLOW_TLS)return 0;
    size_t len=u16(in+3);
    if(len<2||len+5>in_len||pos>=len)return 0;
    memcpy(out,in,3);
    out[3]=(uint8_t)(pos>>8);out[4]=(uint8_t)pos;
    memcpy(out+5,in+5,pos);
    memcpy(out+5+pos,in,3);
    size_t remaining=len-pos;
    out[8+pos]=(uint8_t)(remaining>>8);
    out[9+pos]=(uint8_t)remaining;
    memcpy(out+10+pos,in+5+pos,remaining);
    memcpy(out+10+len,in+5+len,in_len-5-len);
    return in_len+5;
}
