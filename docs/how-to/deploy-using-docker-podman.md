# Deploying Wolpi using Docker/Podman

This assumes you have a `wolpi.yaml` configuration file in the current directory. Add additional
volume mounts as needed to provide access to your images and extensions.

```bash
docker run -p 8080:8080 -v ./wolpi.yaml:/app/wolpi.yaml wolpi:0.1.0
```
