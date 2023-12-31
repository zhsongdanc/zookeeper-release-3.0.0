#                                               -*- Autoconf -*-
# Process this file with autoconf to produce a configure script.

AC_PREREQ(2.59)

AC_INIT([zookeeper C client],2.1.1,[zookeeper-user@lists.sourceforge.net],[c-client-src])
AC_CONFIG_SRCDIR([src/zookeeper.c])
AC_CHECK_FILES([generated/zookeeper.jute.c generated/zookeeper.jute.h],[],
    [AC_MSG_ERROR([jute files are missing! Please run "ant compile_jute" while in the zookeeper top level directory.])
])

# initialize Doxygen support
DX_HTML_FEATURE(ON)
DX_CHM_FEATURE(OFF)
DX_CHI_FEATURE(OFF)
DX_MAN_FEATURE(OFF)
DX_RTF_FEATURE(OFF)
DX_XML_FEATURE(OFF)
DX_PDF_FEATURE(OFF)
DX_PS_FEATURE(OFF)
DX_INIT_DOXYGEN([zookeeper],[c-doc.Doxyfile],[docs])

# initialize automake
AM_INIT_AUTOMAKE([-Wall foreign])
AC_CONFIG_HEADER([config.h])

# Checks for programs.
AM_PATH_CPPUNIT(1.10.2)
AC_PROG_CC
AM_PROG_CC_C_O
AC_PROG_CXX
AC_PROG_INSTALL
AC_PROG_LN_S

# AC_DISABLE_SHARED
AC_PROG_LIBTOOL

AC_ARG_ENABLE([debug],
 [AS_HELP_STRING([--enable-debug],[enable debug build [default=no]])],
 [CFLAGS="-g -O0";CXXFLAGS="-g -O0"],[CFLAGS="-g -O2";CXXFLAGS="-g -O2"])
 
AC_ARG_WITH([syncapi],
 [AS_HELP_STRING([--with-syncapi],[build with support for SyncAPI [default=yes]])],
 [],[with_syncapi=yes])

# Checks for libraries.
AC_CHECK_LIB([pthread], [pthread_mutex_lock],[have_pthread=yes],[have_pthread=no])

if test "x$with_syncapi" != xno && test "x$have_pthread" = xno; then
    AC_MSG_WARN([cannot build SyncAPI -- pthread not found])
    with_syncapi=no
fi
if test "x$with_syncapi" != xno; then
    AC_MSG_NOTICE([building with SyncAPI support])
else
    AC_MSG_NOTICE([building without SyncAPI support])
fi

AM_CONDITIONAL([WANT_SYNCAPI],[test "x$with_syncapi" != xno])

# Checks for header files.
AC_HEADER_STDC
AC_CHECK_HEADERS([arpa/inet.h fcntl.h netdb.h netinet/in.h stdlib.h string.h sys/socket.h sys/time.h unistd.h])

# Checks for typedefs, structures, and compiler characteristics.
AC_C_CONST
AC_C_INLINE
AC_HEADER_TIME
AC_CHECK_TYPE([nfds_t],
    [AC_DEFINE([POLL_NFDS_TYPE],[nfds_t],[poll() second argument type])],
    [AC_DEFINE([POLL_NFDS_TYPE],[unsigned int],[poll() second argument type])],
    [#include <poll.h>])

# Checks for library functions.
AC_CHECK_FUNCS([gethostbyname gettimeofday memmove memset poll socket strchr strdup strerror strtol])

AC_CONFIG_FILES([Makefile])
AC_OUTPUT
