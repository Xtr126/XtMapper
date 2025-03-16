# Base image
FROM mcr.microsoft.com/devcontainers/base:ubuntu

# Install dependencies
RUN apt-get update && apt-get install -y \
    curl unzip openjdk-11-jdk android-sdk \
    && rm -rf /var/lib/apt/lists/*

# Set environment variables
ENV ANDROID_HOME=/usr/local/android-sdk
ENV PATH=$ANDROID_HOME/emulator:$ANDROID_HOME/tools:$ANDROID_HOME/tools/bin:$ANDROID_HOME/platform-tools:$PATH

# Install Android SDK packages
RUN mkdir -p $ANDROID_HOME && \
    yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" "platforms;android-33" "build-tools;33.0.1" "cmdline-tools;latest"

# Set permissions
RUN chmod -R 777 $ANDROID_HOME

# # Set user to vscode
# USER vscode
