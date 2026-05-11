# Deploying Wolpi on Kubernetes

If you run your services in a Kubernetes cluster, this guide helps you set it up. It focuses on
a very basic setup without extensions. It also does not describe how to set up an `Ingress` for
the service, since that is highly dependent on the type of ingress controller you use.

## Prerequisites

- You can deploy resources in a Kubernetes namespace.
- You can access the GitHub Container Repository from the cluster, **or** you have deployed a
  custom container to a private registry that you can access from the cluster.

## Create the runtime configuration

```yaml title="wolpi-configmap.yaml"
apiVersion: v1
kind: ConfigMap
metadata:
  name: wolpi-config
data:
  wolpi.yml: |-
    http:
      host: "0.0.0.0"
      port: 8080
    extension-pool:
      min-idle: 16
      max-idle: 16
      max-total: 16
```

Customize the configuration to fit your environment. This example pins `16`
extension contexts per extension to keep throughput predictable.

!!! note
    `extension-pool` sizing is workload-dependent. Tune these values for your
    traffic and available memory; see
    [Extension Pool Configuration](./optimize-the-configuration-for-better-performance.md#extension-pool-configuration).

## Deploy Wolpi

```yaml title="wolpi-deployment.yaml"
apiVersion: apps/v1
kind: Deployment
metadata:
  name: wolpi
spec:
  replicas: 2
  selector:
    matchLabels:
      app: wolpi
  template:
    metadata:
      labels:
        app: wolpi
    spec:
      containers:
        - name: wolpi
          image: ghcr.io/dbmdz/wolpi:latest
          imagePullPolicy: Always
          ports:
            - containerPort: 8080
              name: http
          volumeMounts:
            - name: wolpi-config
              mountPath: /app/wolpi.yml
              subPath: wolpi.yml
          env:
            - name: JAVA_TOOL_OPTIONS
              value: -XX:MaxRAMPercentage=50.0 -XX:InitialRAMPercentage=12.5
            - name: VIPS_CONCURRENCY
              value: "1"
          resources:
            requests:
              memory: 1Gi
              cpu: "4"
            limits:
              memory: 4Gi
              cpu: "8"
          livenessProbe:
            httpGet:
              path: /monitoring/health/liveness
              port: http
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /monitoring/health/readiness
              port: http
            initialDelaySeconds: 10
            periodSeconds: 5
      volumes:
        - name: wolpi-config
          configMap:
            name: wolpi-config
```

The example starts two replicas. Wolpi itself does not keep request or session state, so serving
traffic scales horizontally. If you install extensions at runtime or otherwise write files under
`/app/data`, make that data available to every replica (e.g. through a shared PVC) or
[bake it into a custom image](./build-containers-with-pre-installed-extensions.md).

To serve your own images, mount a PVC or NFS volume to `/app/images` inside the container so images
can be resolved by relative path. If your identifiers do not map directly to file paths, add an
extension-based resolver such as the [Pattern Resolver](../getting-started/using-an-extension-pattern-resolver.md).

!!! note
    The example uses `ghcr.io/dbmdz/wolpi:latest` for simplicity. For production, pin to a
    specific version or digest so rollouts are reproducible.

!!! note
    The `JAVA_TOOL_OPTIONS`, `VIPS_CONCURRENCY`, and CPU/memory values shown here are starting
    points, not universal defaults. If you need to tune pod resources or runtime settings, see
    the [Memory Usage](./optimize-the-configuration-for-better-performance.md#memory-usage) and
    [Concurrency](./optimize-the-configuration-for-better-performance.md#concurrency) sections in
    the optimization guide. If you use Vertical Pod Autoscaler, start with a conservative
    `extension-pool.min-idle` value and let the pool grow under load instead of pinning many warm
    contexts in memory all the time.

## Expose Wolpi inside the cluster

```yaml title="wolpi-service.yaml"
apiVersion: v1
kind: Service
metadata:
  name: wolpi
  labels:
    app: wolpi
spec:
  selector:
    app: wolpi
  ports:
    - name: http
      protocol: TCP
      port: 80
      targetPort: http
```

!!! note
    If you expose Wolpi through an `Ingress` or reverse proxy on a public host or path prefix,
    make sure it forwards the `Host` and `X-Forwarded-*` headers correctly. If that is not
    possible, set `http.base-uri` in `wolpi.yml` to the public base URL so generated `id` values
    and canonical links use the public URL. See also
    [Expose Wolpi Behind a Reverse Proxy](./expose-wolpi-behind-a-reverse-proxy.md) and the
    [HTTP integration reference](../reference/http.md).

## Add metrics scraping (optional)

If you run the [Prometheus Operator][prom-operator], add a `ServiceMonitor` so Prometheus can scrape
Wolpi metrics.

```yaml title="wolpi-servicemonitor.yaml"
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: wolpi
  labels:
    release: <prometheus-release>
spec:
  namespaceSelector:
    matchNames:
      - <namespace>
  selector:
    matchLabels:
      app: wolpi
  endpoints:
    - port: http
      path: /monitoring/prometheus
      interval: 30s
```

Set the `release` label to whatever your Prometheus installation uses to select `ServiceMonitor`
resources. Some setups use `release: prometheus`, while others use a different label or no label
selector at all.

[prom-operator]: https://prometheus-operator.dev/

## Apply resources

```console
$ kubectl -n <namespace> apply -f wolpi-configmap.yaml
$ kubectl -n <namespace> apply -f wolpi-deployment.yaml
$ kubectl -n <namespace> apply -f wolpi-service.yaml
$ kubectl -n <namespace> apply -f wolpi-servicemonitor.yaml
```

Skip the last command if you do not use Prometheus Operator.

## Verify deployment

```console
$ kubectl -n <namespace> rollout status deploy/wolpi
$ kubectl -n <namespace> get pods -l app=wolpi
$ kubectl -n <namespace> port-forward svc/wolpi 8080:80
```

If you created the `ServiceMonitor`, you can also verify that it exists:

```console
$ kubectl -n <namespace> get servicemonitor wolpi
```

In a second terminal, query Wolpi's built-in validation image. This lets you verify the deployment
without having your own images mounted:

```console
$ curl -s http://localhost:8080/v3/67352ccc-d1b0-11e1-89ae-279075081939/info.json | jq '.sizes'
[
  {
    "type": "Size",
    "width": 1000,
    "height": 1000
  }
]
```

If you've set up Prometheus, run a query to verify Wolpi metrics are being ingested:

```promql
wolpi_extensions_loaded
```

## Next steps

- If you expose the service publicly through an ingress or gateway, see
  [Expose Wolpi Behind a Reverse Proxy](./expose-wolpi-behind-a-reverse-proxy.md).
- For redirects, caching, and response behavior at the IIIF endpoints, see
  [HTTP Integration](../reference/http.md).
- For runtime tuning, see
  [Optimizing Wolpi Configuration](./optimize-the-configuration-for-better-performance.md).
- For extension configuration examples, see [Using Extensions](./install-extensions.md).
