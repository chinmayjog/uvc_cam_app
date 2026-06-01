/* #define to the attribute for default visibility. */
#define DEFAULT_VISIBILITY __attribute__((visibility("default")))

/* #define to 1 to start with debug message logging enabled. */
/* #undef ENABLE_DEBUG_LOGGING */

/* #define to 1 to enable message logging. */
/* #undef ENABLE_LOGGING */

/* #define to 1 if you have the <asm/types.h> header file. */
#define HAVE_ASM_TYPES_H

/* #define to 1 if you have the `clock_gettime' function. */
#define HAVE_CLOCK_GETTIME 1

/* #define to 1 if you have the declaration of `EFD_CLOEXEC', and to 0 if you
   don't. */
#define HAVE_DECL_EFD_CLOEXEC 1

/* #define to 1 if you have the declaration of `EFD_NONBLOCK', and to 0 if you
   don't. */
#define HAVE_DECL_EFD_NONBLOCK 1

/* #define to 1 if you have the declaration of `TFD_CLOEXEC', and to 0 if you
   don't. */
/* #undef HAVE_DECL_TFD_CLOEXEC */

/* #define to 1 if you have the declaration of `TFD_NONBLOCK', and to 0 if you
   don't. */
/* #undef HAVE_DECL_TFD_NONBLOCK */

/* #define to 1 if you have the <dlfcn.h> header file. */
#define HAVE_DLFCN_H 1

/* #define to 1 if the system has eventfd functionality. */
#define HAVE_EVENTFD 1

/* #define to 1 if you have the <inttypes.h> header file. */
#define HAVE_INTTYPES_H 1

/* #define to 1 if you have the `udev' library (-ludev). */
/* #undef HAVE_LIBUDEV */

/* #define to 1 if you have the <memory.h> header file. */
/* #undef HAVE_MEMORY_H */

/* #define to 1 if the system has the type `nfds_t'. */
/* #undef HAVE_NFDS_T */

/* #define to 1 if you have the `pipe2' function. */
#define HAVE_PIPE2

/* #define to 1 if you have the `pthread_condattr_setclock' function. */
#define HAVE_PTHREAD_CONDATTR_SETCLOCK

/* #define to 1 if you have the `pthread_setname_np' function. */
#define HAVE_PTHREAD_SETNAME_NP

/* #define to 1 if you have the `pthread_threadid_np' function. */
/* #undef HAVE_PTHREAD_THREADID_NP */

/* #define to 1 if you have the <stdint.h> header file. */
#define HAVE_STDINT_H

/* #define to 1 if you have the <stdlib.h> header file. */
#define HAVE_STDLIB_H

/* #define to 1 if you have the <strings.h> header file. */
#define HAVE_STRINGS_H

/* #define to 1 if you have the <string.h> header file. */
#define HAVE_STRING_H

/* #define to 1 if the system has the type `struct timespec'. */
#define HAVE_STRUCT_TIMESPEC

/* #define to 1 if you have the `syslog' function. */
#define HAVE_SYSLOG

/* #define to 1 if you have the <sys/stat.h> header file. */
#define HAVE_SYS_STAT_H

/* #define to 1 if you have the <sys/time.h> header file. */
#define HAVE_SYS_TIME_H

/* #define to 1 if you have the <sys/types.h> header file. */
#define HAVE_SYS_TYPES_H

/* #define to 1 if the system has timerfd functionality. */
#define HAVE_TIMERFD

/* #define to 1 if you have the <unistd.h> header file. */
#define HAVE_UNISTD_H

/* #define to the sub-directory where libtool stores uninstalled libraries. */
#define LT_OBJDIR ""

/* Name of package */
#define PACKAGE "libusb-1.0"

/* #define to the address where bug reports for this package should be sent. */
#define PACKAGE_BUGREPORT "libusb-devel@lists.sourceforge.net"

/* #define to the full name of this package. */
#define PACKAGE_NAME "libusb-1.0"

/* #define to the full name and version of this package. */
#define PACKAGE_STRING "libusb-1.0 1.0.24"

/* #define to the one symbol short name of this package. */
#define PACKAGE_TARNAME "libusb-1.0"

/* #define to the home page for this package. */
#define PACKAGE_URL "http://libusb.info"

/* #define to the version of this package. */
#define PACKAGE_VERSION "1.0.24"

/* #define to 1 if compiling for a POSIX platform. */
#define PLATFORM_POSIX

/* #define to 1 if compiling for a Windows platform. */
/* #undef PLATFORM_WINDOWS */


#if defined(__GNUC__)
 #define PRINTF_FORMAT(a, b) __attribute__ ((format (__printf__, a, b)))
#else
 #define PRINTF_FORMAT(a, b)
#endif

/* #define to 1 if you have the ANSI C header files. */
#define STDC_HEADERS 1

/* #define to 1 to output logging messages to the systemwide log. */
/* #undef USE_SYSTEM_LOGGING_FACILITY */

/* Version number of package */
/* #undef VERSION */

/* Enable GNU extensions. */
/* #undef _GNU_SOURCE */

/* #define to the oldest supported Windows version. */
#define _WIN32_WINNT 0x0603

#if defined(WINAPI_FAMILY) && (WINAPI_FAMILY == WINAPI_FAMILY_APP)
#include <Windows.h>
#include <Winsock2.h>

#endif
