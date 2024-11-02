FROM fedora:41

ENV CMDLINE_ZIP=commandlinetools-linux-11076708_latest.zip \
    ANDROID_HOME=/opt/android-sdk \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

RUN dnf install -y git unzip wget java-17-openjdk-devel && dnf clean all && \
    mkdir /opt/android-sdk && \
    wget -q https://dl.google.com/android/repository/$CMDLINE_ZIP && \
    unzip $CMDLINE_ZIP -d /opt/android-sdk/cmdline-tools && \
    rm $CMDLINE_ZIP && mv /opt/android-sdk/cmdline-tools/{cmdline-tools,latest} && \
    yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

COPY artifacts/app /prebuilt/app

WORKDIR /app
