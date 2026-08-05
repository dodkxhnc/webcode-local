#include <jni.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>

/* JNI: int ptyOpen(byte[] nameOut) —— 创建 PTY，返回 master fd，slave 路径写入 nameOut */
JNIEXPORT jint JNICALL
Java_com_webcode_app_termux_TerminalPty_ptyOpen(JNIEnv *env, jobject thiz, jbyteArray nameOut) {
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) return -1;
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        close(master);
        return -1;
    }
    char *name = ptsname(master);
    if (name == NULL) {
        close(master);
        return -1;
    }
    if (nameOut != NULL) {
        jsize len = (*env)->GetArrayLength(env, nameOut);
        int n = (int)strlen(name);
        if (n > len) n = len;
        (*env)->SetByteArrayRegion(env, nameOut, 0, n, (jbyte *)name);
    }
    return master;
}

/* JNI: int ptySetSize(int fd, int rows, int cols) */
JNIEXPORT jint JNICALL
Java_com_webcode_app_termux_TerminalPty_ptySetSize(JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    return ioctl(fd, TIOCSWINSZ, &ws);
}

/* JNI: int ptyRead(int fd, byte[] buf, int off, int len) */
JNIEXPORT jint JNICALL
Java_com_webcode_app_termux_TerminalPty_ptyRead(JNIEnv *env, jobject thiz, jint fd, jbyteArray buf, jint off, jint len) {
    jbyte *tmp = (jbyte *)malloc(len > 0 ? len : 1);
    if (tmp == NULL) return -1;
    ssize_t n = read(fd, tmp, len);
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buf, off, (jsize)n, tmp);
    }
    free(tmp);
    return (jint)n;
}

/* JNI: int ptyWrite(int fd, byte[] buf, int off, int len) */
JNIEXPORT jint JNICALL
Java_com_webcode_app_termux_TerminalPty_ptyWrite(JNIEnv *env, jobject thiz, jint fd, jbyteArray buf, jint off, jint len) {
    jbyte *tmp = (jbyte *)malloc(len > 0 ? len : 1);
    if (tmp == NULL) return -1;
    (*env)->GetByteArrayRegion(env, buf, off, len, tmp);
    ssize_t n = write(fd, tmp, len);
    free(tmp);
    return (jint)n;
}

/* JNI: void ptyClose(int fd) */
JNIEXPORT void JNICALL
Java_com_webcode_app_termux_TerminalPty_ptyClose(JNIEnv *env, jobject thiz, jint fd) {
    close(fd);
}
