# Stage 1: Build the application with OpenJDK
FROM debian:trixie-slim AS builder
WORKDIR /app

RUN apt-get update -q && \
    apt-get install -qq -y --no-install-recommends openjdk-25-jdk maven && \
    rm -rf /var/lib/apt/lists/*

COPY . .

# FIXME: Remove once we have a Maven JDK 25 Image for the CI
# Patch pom.xml so it builds with Java 25
RUN sed -i \
    -e 's/<java.version>24/<java.version>25/g' \
    -e 's/--enable-preview//g' \
    -e 's/<release>24/<release>25/g' \
    pom.xml

RUN mvn -q clean install -DskipTests

# Stage 2: Create the runtime image with GraalVM
FROM debian:trixie-slim
WORKDIR /app

RUN apt-get update -q && \
    apt-get install -qq -y --no-install-recommends libvips42t64 libglib2.0-0t64 ca-certificates curl npm && \
    rm -rf /var/lib/apt/lists/*

# Create symlink for libvips, liblib and libgobject so vips-ffm can find them
RUN ln -s /usr/lib/x86_64-linux-gnu/libvips.so.42 /usr/lib/x86_64-linux-gnu/libvips.so && \
    ln -s /usr/lib/x86_64-linux-gnu/libglib-2.0.so.0 /usr/lib/x86_64-linux-gnu/libglib-2.0.so && \
    ln -s /usr/lib/x86_64-linux-gnu/libgobject-2.0.so.0 /usr/lib/x86_64-linux-gnu/libgobject-2.0.so

ENV GRAALVM_URL="https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_linux-x64_bin.tar.gz"
RUN mkdir -p /opt/graalvm && \
    curl -L "${GRAALVM_URL}" | tar -xz --strip-components=1 -C /opt/graalvm

# Install GraalPy for more compatiblity with native python packages
ENV GRAALPY_URL="https://github.com/oracle/graalpython/releases/download/graal-25.0.0/graalpy-25.0.0-linux-amd64.tar.gz"
RUN mkdir -p /opt/graalpy && \
    curl -L "${GRAALPY_URL}" | tar -xz --strip-components=1 -C /opt/graalpy
RUN ln -s /opt/graalpy/bin/graalpy /usr/local/bin/graalpy

ENV JAVA_HOME="/opt/graalvm"
ENV PATH="/opt/graalvm/bin:${PATH}"

COPY --from=builder /app/target/wolpi-*.jar /app/app.jar

EXPOSE 8080

CMD ["java", "-jar", "--enable-native-access=ALL-UNNAMED", "/app/app.jar"]
