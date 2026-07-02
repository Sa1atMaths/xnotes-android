// The whole JNI surface for code highlighting: stateless calls that parse a
// code block, run a highlight query (.scm) and return flat capture spans, so
// no native handles ever cross the boundary and there are no lifetimes to leak.
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "tree_sitter/api.h"

const TSLanguage *tree_sitter_json(void);

typedef struct {
    const char *name;
    const TSLanguage *(*fn)(void);
} LangEntry;

static const LangEntry LANGS[] = {
    {"json", tree_sitter_json},
};

static const TSLanguage *lang_for(JNIEnv *env, jstring jlang) {
    const char *name = (*env)->GetStringUTFChars(env, jlang, NULL);
    if (!name) return NULL;
    const TSLanguage *result = NULL;
    for (size_t i = 0; i < sizeof(LANGS) / sizeof(LANGS[0]); i++) {
        if (strcmp(LANGS[i].name, name) == 0) {
            result = LANGS[i].fn();
            break;
        }
    }
    (*env)->ReleaseStringUTFChars(env, jlang, name);
    return result;
}

static TSQuery *make_query(const TSLanguage *lang, JNIEnv *env, jbyteArray jscm,
                           uint32_t *err_offset, TSQueryError *err_type) {
    jsize len = (*env)->GetArrayLength(env, jscm);
    jbyte *scm = (*env)->GetByteArrayElements(env, jscm, NULL);
    if (!scm) return NULL;
    TSQuery *query = ts_query_new(lang, (const char *)scm, (uint32_t)len, err_offset, err_type);
    (*env)->ReleaseByteArrayElements(env, jscm, scm, JNI_ABORT);
    return query;
}

// -> flat [startByte, endByte, captureIndex] triples, or null on unknown
//    language / malformed query.
JNIEXPORT jintArray JNICALL
Java_com_xnotes_platform_TreeSitterNative_nativeHighlight(
    JNIEnv *env, jclass cls, jstring jlang, jbyteArray jtext, jbyteArray jscm) {
    const TSLanguage *lang = lang_for(env, jlang);
    if (!lang) return NULL;

    uint32_t err_offset;
    TSQueryError err_type;
    TSQuery *query = make_query(lang, env, jscm, &err_offset, &err_type);
    if (!query) return NULL;

    jsize text_len = (*env)->GetArrayLength(env, jtext);
    jbyte *text = (*env)->GetByteArrayElements(env, jtext, NULL);
    if (!text) {
        ts_query_delete(query);
        return NULL;
    }

    TSParser *parser = ts_parser_new();
    ts_parser_set_language(parser, lang);
    TSTree *tree = ts_parser_parse_string(parser, NULL, (const char *)text, (uint32_t)text_len);

    jintArray out = NULL;
    if (tree) {
        TSQueryCursor *cursor = ts_query_cursor_new();
        ts_query_cursor_exec(cursor, query, ts_tree_root_node(tree));
        uint32_t cap = 256, n = 0;
        jint *buf = malloc(cap * 3 * sizeof(jint));
        TSQueryMatch match;
        while (buf && ts_query_cursor_next_match(cursor, &match)) {
            for (uint16_t i = 0; i < match.capture_count; i++) {
                if (n == cap) {
                    cap *= 2;
                    jint *grown = realloc(buf, cap * 3 * sizeof(jint));
                    if (!grown) { free(buf); buf = NULL; break; }
                    buf = grown;
                }
                TSNode node = match.captures[i].node;
                buf[n * 3 + 0] = (jint)ts_node_start_byte(node);
                buf[n * 3 + 1] = (jint)ts_node_end_byte(node);
                buf[n * 3 + 2] = (jint)match.captures[i].index;
                n++;
            }
        }
        if (buf) {
            out = (*env)->NewIntArray(env, (jsize)(n * 3));
            if (out) (*env)->SetIntArrayRegion(env, out, 0, (jsize)(n * 3), buf);
            free(buf);
        }
        ts_query_cursor_delete(cursor);
        ts_tree_delete(tree);
    }
    ts_parser_delete(parser);
    ts_query_delete(query);
    (*env)->ReleaseByteArrayElements(env, jtext, text, JNI_ABORT);
    return out;
}

// -> the query's capture names, indexed by the captureIndex nativeHighlight emits.
JNIEXPORT jobjectArray JNICALL
Java_com_xnotes_platform_TreeSitterNative_nativeCaptureNames(
    JNIEnv *env, jclass cls, jstring jlang, jbyteArray jscm) {
    const TSLanguage *lang = lang_for(env, jlang);
    if (!lang) return NULL;
    uint32_t err_offset;
    TSQueryError err_type;
    TSQuery *query = make_query(lang, env, jscm, &err_offset, &err_type);
    if (!query) return NULL;

    uint32_t count = ts_query_capture_count(query);
    jclass str = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = (*env)->NewObjectArray(env, (jsize)count, str, NULL);
    for (uint32_t i = 0; out && i < count; i++) {
        uint32_t len;
        const char *name = ts_query_capture_name_for_id(query, i, &len);
        char *copy = malloc(len + 1);
        if (copy) {
            memcpy(copy, name, len);
            copy[len] = 0;
            jstring jname = (*env)->NewStringUTF(env, copy);
            (*env)->SetObjectArrayElement(env, out, (jsize)i, jname);
            (*env)->DeleteLocalRef(env, jname);
            free(copy);
        }
    }
    ts_query_delete(query);
    return out;
}

// -> null when the query compiles, else a short error description (user .scm import).
JNIEXPORT jstring JNICALL
Java_com_xnotes_platform_TreeSitterNative_nativeValidateQuery(
    JNIEnv *env, jclass cls, jstring jlang, jbyteArray jscm) {
    const TSLanguage *lang = lang_for(env, jlang);
    if (!lang) return (*env)->NewStringUTF(env, "unsupported language");
    uint32_t err_offset = 0;
    TSQueryError err_type = TSQueryErrorNone;
    TSQuery *query = make_query(lang, env, jscm, &err_offset, &err_type);
    if (query) {
        ts_query_delete(query);
        return NULL;
    }
    const char *kind;
    switch (err_type) {
        case TSQueryErrorSyntax: kind = "syntax error"; break;
        case TSQueryErrorNodeType: kind = "unknown node type"; break;
        case TSQueryErrorField: kind = "unknown field"; break;
        case TSQueryErrorCapture: kind = "unknown capture"; break;
        case TSQueryErrorStructure: kind = "invalid structure"; break;
        default: kind = "invalid query"; break;
    }
    char msg[96];
    snprintf(msg, sizeof(msg), "%s at byte %u", kind, err_offset);
    return (*env)->NewStringUTF(env, msg);
}
