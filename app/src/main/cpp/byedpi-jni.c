#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

#include "byedpi/error.h"
#include "main.h"

extern int server_fd;
static pthread_mutex_t proxy_mutex = PTHREAD_MUTEX_INITIALIZER;
static int proxy_started;
static int proxy_running;
/* Future V2 engine is isolated from the upstream one-shot proxy. */

int __wrap_daemon(__attribute__((unused)) int nochdir, __attribute__((unused)) int noclose) {
    LOG(LOG_S, "daemon mode is not supported in the native worker");
    errno = EPERM;
    return -1;
}

JNIEXPORT jint JNICALL
Java_io_github_romanvht_byedpi_core_ByeDpiProxy_jniStartProxy(JNIEnv *env, __attribute__((unused)) jobject thiz, jobjectArray args) {
    if (!args) return -1;
    int argc = (*env)->GetArrayLength(env, args);
    // Bound untrusted strategy data before handing it to the C command parser.
    if (argc < 1 || argc > 256) return -1;

    pthread_mutex_lock(&proxy_mutex);
    if (proxy_started) {
        pthread_mutex_unlock(&proxy_mutex);
        LOG(LOG_S, "proxy process already used");
        return -1;
    }
    proxy_started = 1;
    pthread_mutex_unlock(&proxy_mutex);

    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    int result = -1;
    int entered_main = 0;
    size_t total_bytes = 0;
    if (!argv) goto cleanup;

    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!arg) {
            goto cleanup;
        }

        const jsize len = (*env)->GetStringUTFLength(env, arg);
        if (len < 1 || len > 4096 || total_bytes + (size_t)len > 65536) {
            (*env)->DeleteLocalRef(env, arg);
            goto cleanup;
        }
        total_bytes += (size_t)len;

        const char *arg_str = (*env)->GetStringUTFChars(env, arg, NULL);
        if (arg_str) {
            argv[i] = strdup(arg_str);
            (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        }
        (*env)->DeleteLocalRef(env, arg);
        if (!argv[i]) {
            goto cleanup;
        }
    }

    LOG(LOG_S, "starting proxy with %d args", argc);
    pthread_mutex_lock(&proxy_mutex);
    server_fd = -1;
    proxy_running = 1;
    pthread_mutex_unlock(&proxy_mutex);

    entered_main = 1;
    result = main(argc, argv);

    pthread_mutex_lock(&proxy_mutex);
    proxy_running = 0;
    pthread_mutex_unlock(&proxy_mutex);
    LOG(LOG_S, "proxy return code %d", result);

cleanup:
    if (argv) {
        for (int i = 0; i < argc; i++) free(argv[i]);
        free(argv);
    }
    // On argument validation/allocation failure, do not permanently poison
    // the worker. After native main() starts we intentionally keep one-shot
    // lifecycle semantics because upstream globals are not restart-safe.
    if (!entered_main) {
        pthread_mutex_lock(&proxy_mutex);
        proxy_started = 0;
        pthread_mutex_unlock(&proxy_mutex);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_romanvht_byedpi_core_ByeDpiProxy_jniStopProxy(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    LOG(LOG_S, "send shutdown to proxy");
    pthread_mutex_lock(&proxy_mutex);
    int result = proxy_running && server_fd >= 0 ? shutdown(server_fd, SHUT_RDWR) : -1;
    pthread_mutex_unlock(&proxy_mutex);
    return result;
}
