# Deploying Wolpi using Docker/Podman

This assumes you have a `wolpi.yaml` configuration file in the current directory. Add additional
volume mounts as needed to provide access to your images and extensions.

```bash
docker run -p 8080:8080 -v ./wolpi.yaml:/app/wolpi.yaml ghcr.io/dbmdz/wolpi:0.1.2
```

## Container Licensing

**tl;dr for 99.99% of all users**: You're fine 🙂

The Wolpi container image uses Oracle GraalVM for performance reasons. While the application
code is MIT Licensed, the container image itself is subject to the [GraalVM Free Terms and
Conditions][gftc-license], which permits use (including for commercial purposes) and free
redistribution but restricts selling the JDK distribution. You're fine using the container in
production (even commercially, i.e. running a SaaS service), but **if you want to sell the
container image itself (e.g. as part of a commercial enterprise container
subscription service), you need to get a commercial GraalVM license from Oracle.**


[gftc-license]: https://www.oracle.com/downloads/licenses/graal-free-license.html
