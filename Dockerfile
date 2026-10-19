# Stage 1: Build the application with OpenJDK
FROM docker.io/debian:13.7-slim AS builder
WORKDIR /app

ENV LANG=C.UTF-8

RUN apt-get update -q && \
    apt-get install -qq -y --no-install-recommends openjdk-25-jdk maven && \
    rm -rf /var/lib/apt/lists/*

# Download dependencies in a separate layer to allow for caching
COPY pom.xml .
RUN mvn -q dependency:resolve-plugins dependency:go-offline

# And only the copy the full source tree and build
COPY src ./src
RUN mvn package -DskipTests -Dspotless.check.skip=true

# Stage 2: Create the runtime image with GraalVM
FROM docker.io/debian:13.7-slim
WORKDIR /app

ENV LANG=C.UTF-8

# Dependencies for libvips, npm and building of python wheels
RUN apt-get update -q && \
    apt-get install -qq -y --no-install-recommends \
        libvips42t64 libglib2.0-0t64 ca-certificates curl npm build-essential libffi-dev patchelf libjemalloc2 && \
    rm -rf /var/lib/apt/lists/*

# Create symlink for libvips, liblib and libgobject so vips-ffm can find them
RUN ln -s /usr/lib/x86_64-linux-gnu/libvips.so.42 /usr/lib/x86_64-linux-gnu/libvips.so && \
    ln -s /usr/lib/x86_64-linux-gnu/libglib-2.0.so.0 /usr/lib/x86_64-linux-gnu/libglib-2.0.so && \
    ln -s /usr/lib/x86_64-linux-gnu/libgobject-2.0.so.0 /usr/lib/x86_64-linux-gnu/libgobject-2.0.so

# Create symlink for jemalloc and configure it as the allocator
RUN ln -s /usr/lib/*-linux-gnu/libjemalloc.so.2 /usr/local/lib/libjemalloc.so
ENV LD_PRELOAD="/usr/local/lib/libjemalloc.so"
# Configure jemalloc to use background thread for purging dirty and muzzy pages after 5s
ENV MALLOC_CONF="background_thread:true,dirty_decay_ms:5000,muzzy_decay_ms:5000"

ENV GRAALVM_URL="https://gds.oracle.com/download/graal/25/archive/graalvm-jdk-25.0.3_linux-x64_bin.tar.gz"
ARG GRAALVM_SHA256="1b5296613c3d12521d594e1c99302df88d8e1f07d74ab7983dbf572000b92c7c"
RUN set -eux; \
    curl --fail --show-error --location "${GRAALVM_URL}" \
        --output /tmp/graalvm.tar.gz; \
    echo "${GRAALVM_SHA256}  /tmp/graalvm.tar.gz" | sha256sum --check --strict -; \
    mkdir -p /opt/graalvm; \
    tar -xzf /tmp/graalvm.tar.gz --strip-components=1 -C /opt/graalvm; \
    rm /tmp/graalvm.tar.gz

# Install GraalPy for more compatiblity with native python packages
ENV GRAALPY_URL="https://github.com/oracle/graalpython/releases/download/graal-25.0.1/graalpy-25.0.1-linux-amd64.tar.gz"
ARG GRAALPY_SHA256="b5baff786753ebb774300fcf68a9bca4d14cbb8099f055637bee11786abb2d57"
RUN set -eux; \
    curl --fail --show-error --location "${GRAALPY_URL}" \
        --output /tmp/graalpy.tar.gz; \
    echo "${GRAALPY_SHA256}  /tmp/graalpy.tar.gz" | sha256sum --check --strict -; \
    mkdir -p /opt/graalpy; \
    tar -xzf /tmp/graalpy.tar.gz --strip-components=1 -C /opt/graalpy; \
    rm /tmp/graalpy.tar.gz; \
    ln -s /opt/graalpy/bin/graalpy /usr/local/bin/graalpy

# Make sure the output is by default colored when using text logging
ENV SPRING_OUTPUT_ANSI_ENABLED="ALWAYS"
ENV JAVA_HOME="/opt/graalvm"
ENV PATH="/opt/graalvm/bin:${PATH}"

COPY --from=builder /app/target/wolpi-*.jar /app.jar

# Create launcher script
RUN printf '%s\n' \
    '#!/bin/sh' \
    'exec java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -jar /app/app.jar "$@"' \
    > /usr/local/bin/wolpi && \
    chmod +x /usr/local/bin/wolpi

# Unpack the fat jar
RUN java -Djarmode=tools -jar /app.jar extract && \
    rm -rf /app.jar && \
    mv /app/app/* /app && \
    rm -rf /app/app

# Install the IIIF Image API validator
RUN wolpi install-validator && \
    mv /app/data /app/.data_preinstalled && \
    mkdir -p /app/data

# make /app/data writable by the non-root user
RUN chmod 1777 /app/data

USER 10001:10001

EXPOSE 8080

# After the container has launched, copy the preinstalled validator venv to the /app/data directory,
# which might have been bind-mounted from outside
ENTRYPOINT ["/bin/sh", "-c", "cp -r -n /app/.data_preinstalled/. /app/data && exec \"$@\"", "--"]

CMD ["wolpi"]
