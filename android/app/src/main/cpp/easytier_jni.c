#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    const char *key;
    const char *value;
} KeyValuePair;

extern int parse_config(const char *config);
extern int run_network_instance(const char *config);
extern int retain_network_instance(const char **names, size_t length);
extern int collect_network_infos(KeyValuePair *infos, size_t max_length);
extern void get_error_msg(const char **out);
extern void free_string(const char *value);

static int with_string(JNIEnv *env, jstring input, int (*operation)(const char *)) {
    if (input == NULL) return -1;
    const char *value = (*env)->GetStringUTFChars(env, input, NULL);
    if (value == NULL) return -1;
    int result = operation(value);
    (*env)->ReleaseStringUTFChars(env, input, value);
    return result;
}

JNIEXPORT jint JNICALL Java_com_easytier_jni_EasyTierJNI_parseConfig(
        JNIEnv *env, jclass clazz, jstring config) {
    (void) clazz;
    return with_string(env, config, parse_config);
}

JNIEXPORT jint JNICALL Java_com_easytier_jni_EasyTierJNI_runNetworkInstance(
        JNIEnv *env, jclass clazz, jstring config) {
    (void) clazz;
    return with_string(env, config, run_network_instance);
}

JNIEXPORT jint JNICALL Java_com_easytier_jni_EasyTierJNI_retainNetworkInstance(
        JNIEnv *env, jclass clazz, jobjectArray names) {
    (void) clazz;
    if (names == NULL) return retain_network_instance(NULL, 0);
    jsize length = (*env)->GetArrayLength(env, names);
    if (length <= 0) return retain_network_instance(NULL, 0);
    const char **native_names = calloc((size_t) length, sizeof(char *));
    jstring *java_names = calloc((size_t) length, sizeof(jstring));
    if (native_names == NULL || java_names == NULL) {
        free(native_names); free(java_names); return -1;
    }
    int loaded = 0;
    for (jsize i = 0; i < length; i++) {
        java_names[i] = (jstring) (*env)->GetObjectArrayElement(env, names, i);
        if (java_names[i] == NULL) break;
        native_names[i] = (*env)->GetStringUTFChars(env, java_names[i], NULL);
        if (native_names[i] == NULL) break;
        loaded++;
    }
    int result = loaded == length ? retain_network_instance(native_names, (size_t) length) : -1;
    for (int i = 0; i < loaded; i++) {
        (*env)->ReleaseStringUTFChars(env, java_names[i], native_names[i]);
        (*env)->DeleteLocalRef(env, java_names[i]);
    }
    free(native_names); free(java_names);
    return result;
}

JNIEXPORT jstring JNICALL Java_com_easytier_jni_EasyTierJNI_getLastError(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    const char *message = NULL;
    get_error_msg(&message);
    if (message == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, message);
    free_string(message);
    return result;
}

JNIEXPORT jstring JNICALL Java_com_easytier_jni_EasyTierJNI_collectNetworkInfos(
        JNIEnv *env, jclass clazz, jint max_length) {
    (void) clazz;
    size_t limit = max_length <= 0 ? 0 : (size_t) (max_length > 64 ? 64 : max_length);
    if (limit == 0) return (*env)->NewStringUTF(env, "{\"map\":{}}");
    KeyValuePair *infos = calloc(limit, sizeof(KeyValuePair));
    if (infos == NULL) return NULL;
    int count = collect_network_infos(infos, limit);
    if (count < 0) { free(infos); return NULL; }
    size_t capacity = 64;
    for (int i = 0; i < count; i++) capacity += strlen(infos[i].key) * 2 + strlen(infos[i].value) + 8;
    char *json = calloc(capacity, 1);
    if (json == NULL) { free(infos); return NULL; }
    strcpy(json, "{\"map\":{");
    size_t used = strlen(json);
    for (int i = 0; i < count; i++) {
        if (i) json[used++] = ',';
        json[used++] = '\"';
        for (const char *p = infos[i].key; *p && used + 3 < capacity; p++) {
            if (*p == '\"' || *p == '\\') json[used++] = '\\';
            json[used++] = *p;
        }
        json[used++] = '\"'; json[used++] = ':';
        size_t value_length = strlen(infos[i].value);
        memcpy(json + used, infos[i].value, value_length); used += value_length;
        free_string(infos[i].key); free_string(infos[i].value);
    }
    memcpy(json + used, "}}", 3);
    jstring result = (*env)->NewStringUTF(env, json);
    free(json); free(infos);
    return result;
}

