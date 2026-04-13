# Deploying Wolpi on Kubernetes

You can deploy Wolpi in Kubernetes using the following example manifest, which adds a `Deployment`
and a `Service` to expose it within the cluster. To expose it outside the cluster, you can add an
`Ingress` or `Gateway` + `HTTPRoute` resource depending on your setup.

```yaml title="wolpi-k8s.yaml"
apiVersion: v1
kind: ConfigMap
metadata:
  name: wolpi-config
data:
  wolpi.yaml: |
    http:
        host: 0.0.0.0
        port: 8080
    logging:
      level: info
      format: json
      log-request-details-on-crash: false
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: wolpi
spec:
  replicas: 1
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
          image: wolpi:0.1.0
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
              name: http
          volumeMounts:
            # Add a mount for your images here, if needed
            - name: wolpi-config
              mountPath: /app/wolpi.yaml
              subPath: wolpi.yaml
          env:
            - name: JAVA_TOOL_OPTIONS
              # Use at most 50% of the container memory for the JVM heap, and start with 12.5%
              # This is needed since libvips uses off-heap memory that also needs to fit into the
              # container's memory limits
              value: "-XX:MaxRAMPercentage=50.0 -XX:InitialRAMPercentage=12.5"
          resources:
            requests:
              memory: "1Gi"
              cpu: "4"
            limits:
              # Wolpi by itself doesn't need a lot of memory since vips loads images in a
              # streaming fashion, adjust this based on your metrics and expected maximum number
              # of concurrent requests
              memory: "4Gi"
              # Adjust CPU limits according to your expected load and available hardware
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
---
apiVersion: v1
kind: Service
metadata:
  name: wolpi
spec:
  selector:
    app: wolpi
  ports:
    - protocol: TCP
      port: 80
      targetPort: http
```
