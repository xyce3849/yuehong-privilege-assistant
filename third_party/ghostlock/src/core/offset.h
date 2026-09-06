#ifndef TARGET_CONFIG_H
#error "TARGET_CONFIG_H is not defined; build with -DTARGET_CONFIG_H=..."
#endif

/* Stringify macro so TARGET_CONFIG_H can be passed as -DTARGET_CONFIG_H=target.h
 * (without quotes).  The .cmd NDK wrapper strips embedded quotes from -D args. */
#define GL_STRINGIFY(x) #x
#define GL_INCLUDE_STR(x) GL_STRINGIFY(x)
#include GL_INCLUDE_STR(TARGET_CONFIG_H)
