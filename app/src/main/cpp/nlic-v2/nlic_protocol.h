#ifndef NLIC_PROTOCOL_H
#define NLIC_PROTOCOL_H
#include <stddef.h>
#include <stdint.h>
typedef enum { NLIC_FLOW_OTHER=0, NLIC_FLOW_TLS=1, NLIC_FLOW_HTTP=2, NLIC_FLOW_QUIC_INITIAL=3 } nlic_flow_type;
nlic_flow_type nlic_detect(const uint8_t *p, size_t n, int udp);
size_t nlic_tls_sni_midpoint(const uint8_t *p, size_t n);
size_t nlic_tls_split_record(const uint8_t *in, size_t in_len,
                             uint8_t *out, size_t cap, size_t payload_offset);
#endif
