#ifndef NLIC_SOCKS_H
#define NLIC_SOCKS_H
#include <stdint.h>
typedef struct {
    uint16_t listen_port;
    char username[65];
    char password[129];
    int tls_record_split;
    unsigned max_connections;
    unsigned idle_seconds;
} nlic_config;
int nlic_v2_run(const nlic_config *config);
void nlic_v2_stop(void);
#endif
