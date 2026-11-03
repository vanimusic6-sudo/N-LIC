#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>

#include "byedpi/error.h"

static pthread_once_t tunnel_once = PTHREAD_ONCE_INIT;
static int (*tunnel_main)(const unsigned char *, unsigned int, int);
static void (*tunnel_quit)(void);

static void load_tunnel(void) {
    void *library = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        LOG(LOG_S, "failed to load tunnel: %s", dlerror());
        return;
    }

    tunnel_main = (int (*)(const unsigned char *, unsigned int, int)) dlsym(library, "hev_socks5_tunnel_main_from_str");
    tunnel_quit = (void (*)(void)) dlsym(library, "hev_socks5_tunnel_quit");
}

JNIEXPORT jint JNICALL
Java_io_github_romanvht_byedpi_core_TProxyService_startTunnel(JNIEnv *env, __attribute__((unused)) jobject thiz, jstring config, jint fd) {
    // A malformed FD or extremely large YAML must not reach the native parser.
    if (!config || fd < 0) return -1;
    jsize config_len_jni = (*env)->GetStringUTFLength(env, config);
    if (config_len_jni < 1 || config_len_jni > 512 * 1024) return -1;

    pthread_once(&tunnel_once, load_tunnel);
    if (!tunnel_main || !tunnel_quit) {
        LOG(LOG_S, "tunnel entry points unavailable");
        return -1;
    }

    const char *config_str = (*env)->GetStringUTFChars(env, config, NULL);
    if (!config_str) {
        return -1;
    }
    unsigned int config_len = (unsigned int)config_len_jni;
    int result = tunnel_main((const unsigned char *) config_str, config_len, fd);
    (*env)->ReleaseStringUTFChars(env, config, config_str);
    return result;
}

JNIEXPORT void JNICALL
Java_io_github_romanvht_byedpi_core_TProxyService_stopTunnel(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    pthread_once(&tunnel_once, load_tunnel);
    if (tunnel_quit) {
        tunnel_quit();
    }
}
