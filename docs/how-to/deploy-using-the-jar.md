# Deploying Wolpi using the JAR

This assumes you have an up-to-date (we recommend Java 25) Java Runtime Environment installed.
If you are using extensions, we *highly* recommend using the [GraalVM distribution of Java][graal-jdk]
for  best performance with your extensions.

**TODO:**
- Additional requirements (libvips, GraalPy and Node for extensions)

```bash
# Download the latest JAR from the releases page
wget https://github.com/dbmdz/wolpi/releases/download/0.1.0/wolpi-0.1.0.jar
# With the configuration file at ./wolpi.yaml
java -jar wolpi-0.1.0.jar
# With a custom configuration file
java -jar wolpi-0.1.0.jar --config=/etc/wolpi/wolpi.yaml
```

## Using a systemd service
We recommend creating a dedicated user for running Wolpi, e.g. `wolpi` to improve security.

This systemd unit file assumes you have downloaded the Wolpi JAR to `/opt/wolpi/wolpi.jar` and
have your configuration file at `/etc/wolpi/wolpi.yaml`.

```systemd title="wolpi.service"
[Unit]
Description=Wolpi IIIF Image Server
After=network.target

[Service]
Type=exec
# The flags are only to silence some warnings caused by the use of the Graal Polyglot API
ExecStart=/usr/bin/java \
    --enable-native-access=ALL-UNNAMED \
    --sun-misc-unsafe-memory-access=allow \
    -jar /opt/wolpi/wolpi.jar --config=/etc/wolpi/wolpi.yaml
WorkingDirectory=/opt/wolpi
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```



[graal-jdk]: https://www.graalvm.org/downloads/
