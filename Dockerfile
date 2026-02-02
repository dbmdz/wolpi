# Stage 1: Build the application with OpenJDK
FROM docker.io/debian:13.2-slim AS builder
WORKDIR /app

ENV LANG=C.UTF-8

RUN apt-get update -q && \
    apt-get install -qq -y --no-install-recommends openjdk-25-jdk maven && \
    rm -rf /var/lib/apt/lists/*

# Download dependencies in a separate layer to allow for caching
COPY pom.xml .
RUN mvn -q dependency:resolve-plugins dependency:go-offline

# And only the copy the full source tree and build
COPY . .
RUN mvn package -DskipTests -Dspotless.check.skip=true

# Stage 2: Create the runtime image with GraalVM
FROM docker.io/debian:13.2-slim
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

ENV GRAALVM_URL="https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_linux-x64_bin.tar.gz"
RUN mkdir -p /opt/graalvm && \
    curl -L "${GRAALVM_URL}" | tar -xz --strip-components=1 -C /opt/graalvm

# Install GraalPy for more compatiblity with native python packages
ENV GRAALPY_URL="https://github.com/oracle/graalpython/releases/download/graal-25.0.1/graalpy-25.0.1-linux-amd64.tar.gz"
RUN mkdir -p /opt/graalpy && \
    curl -L "${GRAALPY_URL}" | tar -xz --strip-components=1 -C /opt/graalpy
RUN ln -s /opt/graalpy/bin/graalpy /usr/local/bin/graalpy

# Make sure the output is by default colored when using text logging
ENV SPRING_OUTPUT_ANSI_ENABLED="ALWAYS"
ENV JAVA_HOME="/opt/graalvm"
ENV PATH="/opt/graalvm/bin:${PATH}"

COPY --from=builder /app/target/wolpi-*.jar /app.jar

RUN java -Djarmode=tools -jar /app.jar extract && \
    rm -rf /app.jar && \
    mv /app/app/* /app && \
    rm -rf /app/app

RUN java --enable-native-access=ALL-UNNAMED \
         --sun-misc-unsafe-memory-access=allow \
         -jar /app/app.jar \
         install-validator && \
    cp -al /app/data /app/.data_preinstalled

EXPOSE 8080

# Copy the preinstalled validator venv to the /app/data directory, which might
# have been bind-mounted from outside
ENTRYPOINT ["/bin/sh", "-c", "cp -r -n /app/.data_preinstalled/. /app/data && exec \"$@\"", "--"]

CMD ["java", "-jar", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "/app/app.jar"]
