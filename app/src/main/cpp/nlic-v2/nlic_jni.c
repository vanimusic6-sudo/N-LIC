#include <jni.h>
#include <string.h>
#include "nlic_socks.h"

/* JNI accepts no remote bind addresses or arbitrary native commands.
 * Loopback-only listener and ephemeral user/password are enforced in C.
 */
JNIEXPORT jint JNICALL
Java_io_github_romanvht_byedpi_core_NlicV2Proxy_nativeRun(
        JNIEnv *env,jobject thiz,jint port,jstring user,jstring pass,
        jboolean tlsRecordSplit) {
    (void)thiz;
    if(port<=0||port>65535||!user||!pass)return -1;
    const char *u=(*env)->GetStringUTFChars(env,user,NULL);
    if(!u)return -1;
    const char *p=(*env)->GetStringUTFChars(env,pass,NULL);
    if(!p) {
        (*env)->ReleaseStringUTFChars(env,user,u);
        return -1;
    }
    nlic_config cfg={0};
    cfg.listen_port=(uint16_t)port;
    size_t ul=strlen(u),pl=strlen(p);
    int status=-1;
    if(ul>=3&&ul<sizeof(cfg.username)&&pl>=24&&pl<sizeof(cfg.password)) {
        memcpy(cfg.username,u,ul+1);
        memcpy(cfg.password,p,pl+1);
        cfg.tls_record_split=tlsRecordSplit==JNI_TRUE;
        cfg.max_connections=48;
        cfg.idle_seconds=180;
        status=nlic_v2_run(&cfg);
    }
    (*env)->ReleaseStringUTFChars(env,user,u);
    (*env)->ReleaseStringUTFChars(env,pass,p);
    memset(&cfg,0,sizeof(cfg));
    return status;
}
JNIEXPORT void JNICALL
Java_io_github_romanvht_byedpi_core_NlicV2Proxy_nativeStop(
        JNIEnv *env,jobject thiz) {
    (void)env;(void)thiz;
    nlic_v2_stop();
}
