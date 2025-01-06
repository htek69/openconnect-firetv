NDK := /opt/android-sdk-linux_x86/android-ndk-r27c

JAR_DIR := ../app/libs
JNI_DIR := ../app/src/main/jniLibs
BIN_DIR := ../app/src/main/assets/raw

ARCH_LIST := arm arm64 x86 x86_64

# The __ANDROID_API__ version needs to stay in sync with
# openconnect/android/Makefile

API_LEVEL := 23
EXTRA_CFLAGS := -Oz -D__ANDROID_API__=$(API_LEVEL) -Wno-macro-redefined

NDK_ARCH_arm := armeabi
TRIPLET_arm := armv7a-linux-androideabi
CFLAGS_arm := -march=armv7-a -mthumb $(EXTRA_CFLAGS)

NDK_ARCH_arm64 := arm64-v8a
TRIPLET_arm64 := aarch64-linux-android
CFLAGS_arm64 := $(EXTRA_CFLAGS)

NDK_ARCH_x86 := x86
TRIPLET_x86 := i686-linux-android
CFLAGS_x86 := $(EXTRA_CFLAGS)

NDK_ARCH_x86_64 := x86_64
TRIPLET_x86_64 := x86_64-linux-android
CFLAGS_x86_64 := $(EXTRA_CFLAGS)

JAR_LIST := openconnect-wrapper.jar stoken-wrapper.jar
PKG_LIST := curl

ARCH_TARGETS := $(addprefix arch-,$(ARCH_LIST))

ARCH_CURL_TARGETS := $(addsuffix /curl,$(ARCH_LIST))
ARCH_STOKEN_TARGETS := $(addsuffix /libstoken.so,$(ARCH_LIST))
ARCH_OC_TARGETS := $(addsuffix /libopenconnect.so,$(ARCH_LIST))
ARCH_SYSROOT_TARGETS := $(addsuffix /sysroot,$(ARCH_LIST))

INSTALL_ARCH_TARGETS := $(addprefix install-arch-,$(ARCH_LIST))
INSTALL_JAR_TARGETS := $(addprefix install-jar-,$(JAR_LIST))

CURL_VER := 8.10.1

TOPDIR := $(shell pwd)

TOOLCHAIN = $(NDK)/toolchains/llvm/prebuilt/linux-x86_64

get_sysroot = $(TOPDIR)/$(1)/sysroot/usr
# get_sysroot = $(TOPDIR)/$1/openconnect/android/$(TRIPLET_$*)/sysroot/usr
pkg_config = PKG_CONFIG_LIBDIR="$(call get_sysroot,$(1))/lib/pkgconfig" pkg-config
set_build_env = PATH=$(call get_toolchain,$(1))/bin:$$PATH \
	PKG_CONFIG_LIBDIR=$(call get_sysroot,$(1))/lib/pkgconfig \
	AR="$(TOOLCHAIN)/bin/llvm-ar" \
	AS="$(TOOLCHAIN)/bin/llvm-as" \
	CC="$(TOOLCHAIN)/bin/$(TRIPLET_$(1))$(API_LEVEL)-clang --sysroot=$(call get_sysroot,$(1))/.." \
	CXX="$(TOOLCHAIN)/bin/$(TRIPLET_$(1))$(API_LEVEL)-clang++ --sysroot=$(call get_sysroot,$(1))/.." \
	LD="$(TOOLCHAIN)/bin/ld --sysroot=$(call get_sysroot,$(1))/.." \
	RANLIB="$(TOOLCHAIN)/bin/llvm-ranlib" \
	STRIP="$(TOOLCHAIN)/bin/llvm-strip"

.PHONY: install
install: $(INSTALL_ARCH_TARGETS) $(INSTALL_JAR_TARGETS)

.PHONY: clean
clean:
	rm -rf $(ARCH_LIST) $(JAR_LIST) $(PKG_LIST)

.PHONY: $(INSTALL_JAR_TARGETS)
$(INSTALL_JAR_TARGETS) : install-jar-% : %
	mkdir -p $(JAR_DIR)
	cp $* $(JAR_DIR)/

.PHONY: $(INSTALL_ARCH_TARGETS)
$(INSTALL_ARCH_TARGETS) : install-arch-% : arch-%
	mkdir -p $(JNI_DIR)/$(NDK_ARCH_$*)
	cp $*/libopenconnect.so $(JNI_DIR)/$(NDK_ARCH_$*)/
	cp $*/libstoken.so $(JNI_DIR)/$(NDK_ARCH_$*)/
	mkdir -p $(BIN_DIR)/$(NDK_ARCH_$*)
	cp $*/curl $(BIN_DIR)/$(NDK_ARCH_$*)/curl-bin

.PHONY: $(ARCH_TARGETS)
$(ARCH_TARGETS) : arch-% : %/curl %/libstoken.so %/libopenconnect.so

curl/configure:
	mkdir -p curl
	tar -C curl --strip 1 -Jxf dist/curl-$(CURL_VER).tar.xz
	touch $@

$(ARCH_CURL_TARGETS) : %/curl : %/sysroot curl/configure
	mkdir -p $*/build-curl $*/out
	cd $*/build-curl && \
		$(call set_build_env,$*) $(TOPDIR)/curl/configure \
			--prefix=/ --host=$(TRIPLET_$*) --disable-shared \
			--enable-static --with-gnutls --without-libpsl \
			CFLAGS="$(CFLAGS_$*) -fvisibility=default -fPIE" \
			LDFLAGS="-rdynamic -fPIE -pie" \
			LIBS="$(shell $(call pkg_config,$*) --static --libs gnutls)"
	$(call set_build_env,$*) $(MAKE) -C $*/build-curl
	$(call set_build_env,$*) $(MAKE) -C $*/build-curl install-strip \
		DESTDIR=$(TOPDIR)/$*/out
	cp -L $*/out/bin/curl $*/

stoken/configure:
	cd stoken && bash autogen.sh && chmod +x configure
	touch $@

$(ARCH_STOKEN_TARGETS) : %/libstoken.so : %/sysroot stoken/configure
	mkdir -p $*/build-stoken $*/out
	cd $*/build-stoken && \
		$(call set_build_env,$*) $(TOPDIR)/stoken/configure \
			--prefix=/ --host=$(TRIPLET_$*) --disable-static \
			--enable-shared --without-gtk \
			--with-java=$(call get_sysroot,$*)/include \
			--enable-jni-standalone --disable-symvers \
                        CFLAGS="$(CFLAGS_$*)" LDFLAGS="-Wl,--strip-debug"
	$(call set_build_env,$*) $(MAKE) -C $*/build-stoken
	$(call set_build_env,$*) $(MAKE) -C $*/build-stoken install-strip \
		DESTDIR=$(TOPDIR)/$*/out
	cp -L $*/out/lib/libstoken.so $*/

$(ARCH_OC_TARGETS) : %/libopenconnect.so : .openconnect-sources
	# rm -rf $*/openconnect
	mkdir -p $*/openconnect
	cp -a openconnect $*/
	$(MAKE) -C $*/openconnect/android ARCH=$* NDK=$(NDK)
	cp -Lf $*/openconnect/android/$(TRIPLET_$*)/out/lib/libopenconnect.so $@

.openconnect-sources:
	$(MAKE) -C openconnect/android sources
	touch $@

# use openconnect to run make-standalone-toolchain.sh and install
# GnuTLS/nettle into the sysroot for us
$(ARCH_SYSROOT_TARGETS) : %/sysroot : %/libopenconnect.so
	ln -sf openconnect/android/$(TRIPLET_$*)/sysroot $*/

openconnect-wrapper.jar:
	cd openconnect/java && ant
	cp openconnect/java/dist/$@ .

stoken-wrapper.jar:
	cd stoken/java && ant
	cp stoken/java/dist/$@ .

# note: this skips curl and libstoken as they rarely change
.PHONY: refresh
refresh:
	cd openconnect && \
		git remote update -p && \
		git checkout -f origin/master
	rev=`cd openconnect && git rev-parse --short HEAD`; \
		git commit openconnect -sm \
			"external: Sync with openconnect rev $$rev"
	$(MAKE) clean
	$(MAKE) install
