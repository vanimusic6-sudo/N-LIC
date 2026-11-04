#ifndef NLIC_PROTOCOL_H
#define NLIC_PROTOCOL_H
#include <stddef.h>
#include <stdint.h>
typedef enum { NLIC_FLOW_OTHER=0, NLIC_FLOW_TLS=1, NLIC_FLOW_HTTP=2, NLIC_FLOW_QUIC_INITIAL=3 } nlic_flow_type;
nlic_flow_type nlic_detect(const uint8_t *p, size_t n, int udp);
/* Only a complete, well-formed ClientHello can be transformed.
 * Offsets refer to TLS record payload, not TCP segments. */
int nlic_tls_sni_range(const uint8_t *p, size_t n,
                       size_t *start, size_t *end);
size_t nlic_tls_sni_midpoint(const uint8_t *p, size_t n);
/* Three valid TLS records: before SNI, first SNI portion, remainder.
 * Returns zero if a safe split cannot be made; never modify handshake
 * contents, version, encryption parameters, or certificate checks. */
size_t nlic_tls_split_sni_record(const uint8_t *in, size_t in_len,
                                 uint8_t *out, size_t cap);
size_t nlic_tls_split_record(const uint8_t *in, size_t in_len,
                             uint8_t *out, size_t cap, size_t payload_offset);
#endif
