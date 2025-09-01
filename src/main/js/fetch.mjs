const HttpRequest = Java.type('java.net.http.HttpRequest');
const BodyPublishers = Java.type('java.net.http.HttpRequest.BodyPublishers');
const BodyHandlers = Java.type('java.net.http.HttpResponse.BodyHandlers');
const URI = Java.type('java.net.URI');
const JavaString = Java.type('java.lang.String');
const StandardCharsets = Java.type('java.nio.charset.StandardCharsets');

/** A bare-bones synchronous `fetch` implementation using Java's HttpClient. */
export default function fetchSync(url, options = {}) {
  const builder = HttpRequest.newBuilder(URI.create(url));
  const method = options.method || 'GET';

  let bodyPublisher;
  if (!options.body) {
    bodyPublisher = BodyPublishers.noBody();
  } else if (typeof options.body === 'string') {
    bodyPublisher = BodyPublishers.ofString(options.body);
  } else if (options.body instanceof Uint8Array) {
    // GraalJS automatically converts the Uint8Array to a Java byte[]
    bodyPublisher = BodyPublishers.ofByteArray(options.body);
  } else {
    throw new Error('Unsupported body type. Use string or Uint8Array.');
  }
  builder.method(method.toUpperCase(), bodyPublisher);

  if (options.headers) {
    for (const [key, value] of Object.entries(options.headers)) {
      builder.header(key, String(value));
    }
  }

  const request = builder.build();

  const javaResponse = wolpi.httpClient().send(request, BodyHandlers.ofByteArray());
  const bodyBytes = javaResponse.body();

  const headers = {};
  const headerMap = javaResponse.headers().map();
  headerMap.keySet().forEach(key => {
    headers[key] = headerMap.get(key).get(0);
  });

  return {
    ok: javaResponse.statusCode() >= 200 && javaResponse.statusCode() < 300,
    status: javaResponse.statusCode(),
    headers: headers,
    array: () => new Uint8Array(bodyBytes),
    arrayBuffer: () => (new Uint8Array(bodyBytes)).buffer,
    text: () => new JavaString(bodyBytes, StandardCharsets.UTF_8),
    json: () => JSON.parse(new JavaString(bodyBytes, StandardCharsets.UTF_8)),
  };
}
