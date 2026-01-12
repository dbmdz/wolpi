# Extensions

??? bug "TODO"
    - Split into "Using Extensions" and "Developing Extensions" to reduce complexity for non-developers

Wolpi's functionality can be extended with custom logic written in JavaScript or Python. This allows
for integration with various authentication providers and image sources as well as customizing
the standard processing pipelines with extra syntax or entirely custom behavior.

## Hooks

Wolpi extensions work by implementing one or more "hooks". A hook is a function that is called by
Wolpi at a specific point in its processing pipeline. If a hook in an extension returns a non-null
value, the standard implementation for that processing step is skipped and the result of the hook
is used instead. If multiple extensions implement the same hook, the behavior depends on the hook
and is described in the respective hook documentation below.

!!! tip "Limitation: Do not violate the IIIF Specification!"

    Wolpi's Extension API allows customization of pretty much every aspect of the image processing pipeline.
    With great power comes a bit of responsibility, though: Wolpi is designed to be a IIIF-compliant
    image server, and **extensions must not violate the IIIF specification**. This means that extensions
    must ensure that every part of the official IIIF Image API specification is adhered to, including
    parameter parsing, behavior, and error handling. On startup, Wolpi runs every enabled extension
    through the IIIF Image API conformance tests to ensure compliance, and refuses to start if any
    enabled extension causes a violation.

    Practically, as an extension developer, this means:

    - If possible, gate your custom logic behind syntax that does not conflict with IIIF syntax. For
      example, if you want to implement a custom cropping behavior, use a custom syntax that is not
      valid IIIF syntax (e.g., `customCrop:x,y,w,h` instead of `x,y,w,h`).
    - In some cases you may want to implement custom behavior that replaces standard IIIF behavior
      (e.g., a custom scaling algorithm). In these cases, ensure that your implementation adheres
      to the IIIF specification in terms of parameter parsing, behavior, and error handling.
    - Run your extension against the IIIF Image API conformance tests to ensure compliance. You can
      do this with the `validate` subcommand of the Wolpi application:
      ```bash
      java -jar wolpi.jar validate path/to/your/extension-pkg
      ```
      You can add `-w` to automatically run the tests whenever the extension code changes.



### `info` Hook

Every extension must implement the `info` hook to return basic information about itself, such as
its name and description.

!! warning "Do not use this hook for initialization code!"

    The `info` hook is called by Wolpi on startup to gather information about the extension.
    It is executed in a different context than the one used when processing requests, so any
    modifications to the extension's state in this hook will not be visible during request processing.
    If you need to run initialization code for your extension, do so by checking for uninitialized
    state when you need it, and initializing it on first use.


??? bug "TODO"
    Do we need an additional `initialize` Hook that gets executed whenever an extension is first
    loaded into the pool? This would be a good place to run expensive setup code that only needs
    to be run once per extension instance.


=== "JavaScript"
    ```typescript
    function info(): {
        apiVersion: 1; // (1)!
        // Name of the extension, should be unique among all enabled extensions
        name: string;
        // An optional, brief description of what the extension does
        description?: string;
    };
    ```

    1.  This is currently hardcoded to `1`, as there is only one version of the
        extension API. In the future, breaking changes to the API will increment
        this number to ensure that we can distinguish older extensions from newer
        ones and prevent breakage

=== "Python"
    ```python
    class ExtensionInfo(TypedDict):# (1)!
        apiVersion: Literal[1]# (2)!
        name: str
        description: NotRequired[str]

    def info() -> ExtensionInfo: ...
    ```

    1.  In Python, you can either return a `dict` with the required entries as
        specified by this `TypedDict` definition or a class with the same attributes
        as instance fields. This goes for all of the subsequent `TypedDict` definitions
        in this documentation.
    2.  This is currently hardcoded to `1`, as there is only one version of the
        extension API. In the future, breaking changes to the API will increment
        this number to ensure that we can distinguish older extensions from newer
        ones and prevent breakage

### `cleanup` Hook

Every extension **must** implement the `cleanup` hook, even if it does nothing. This hook is called
after the response has been sent to the client and must be used to clean up any state that was
accumulated during the processing of a request and should not persist between requests.

=== "JavaScript"
    ```typescript
    function cleanup(): void;
    ```

=== "Python"
    ``` python
    def cleanup() -> None: ...
    ```

### `authorize` Hook
The `authorize` hook is called before any processing is done for a request to determine if the request
is authorized to access the requested image. The hook receives the identifier, the request headers and
a string with the original client IP that made the request. If the request is authorized, the hook
must return `true`. If the request is not authorized, the hook must return `false`.


=== "JavaScript"
    ```typescript
    function authorize(
        identifier: string,
        headers: Record<string, string>,
        clientIp: string
    ): boolean;
    ```

=== "Python"
    ``` python
    def authorize(
        identifier: str,
        headers: dict[str, str],
        client_ip: str
    ) -> bool: ...
    ```

If there are multiple extensions that implement the `authorize` hook, Wolpi will call them in the order
they are configured until one of them returns `false`. If all extensions return `true`, the request
is considered authorized.

### `resolve` Hook

The `resolve` hook is called to resolve an image identifier to an image source. The hook receives
the image identifier as well as caching information from the client (`ETag` and `Last-Modified` headers,
if present on the request) and must return a value that describes how to access the image, if it
can be resolved by this extension. Additionally, the return value can contain metadata about the image
(such as width, height, format, etc.) to avoid having Wolpi read the image from the source just to extract
this information for `info.json` requests.

=== "JavaScript"
    ```typescript
    /// An image file in a file system accessible to Wolpi
    interface FilesystemResolvedImage {
        path: string;
    }

    /// A data blob containing the raw (still encoded) image data,
    /// will be decoded by libvips
    interface BinaryResolvedImage {
        rawData: Uint8Array;
    }

    /// An image accessible via HTTP(S), optionally with custom request
    /// headers (e.g. for auth) and a flag indicating whether byte-range
    /// requests are supported by the endpoint
    interface HttpResolvedImage {
        url: string;
        headers?: Record<string, string>;
        supportsByteRange?: boolean;
    }

    /// A custom data source that libvips will read from using the
    /// provided callbacks, can be more efficient for reading very
    /// large images from e.g. databases or object storage systems
    /// that do not support HTTP with byte-range requests
    interface CustomSourceResolvedImage {
        /// `whence` denotes the position to seek from:
        ///  0 (beginning of file), 1 (current position)
        ///  or 2 (end of file)
        onSeek(offset: number, whence: number): number;

        /// The returned buffer will be copied, feel free to
        /// reuse internal buffers for subsequent calls
        onRead(length: number): Uint8Array;
    }

    type ResolvedImage =
        FilesystemResolvedImage |
        BinaryResolvedImage |
        HttpResolvedImage |
        CustomSourceResolvedImage;

    /// Indicate that the source has not been modified since it was
    /// last accessed by the client, determined from the caching headers
    /// passed to the resolving hook
    interface SourceNotModified {
        notModified: true;
    }

    /// Set of metadata about the image that can be provided by the
    /// resolver to avoid having Wolpi read the image just to extract
    /// this information for `info.json` requests
    interface ImageInfo {
        nativeSize: {
            width: number;
            height: number;
        };

        /// Sizes that are directly encoded in the image, e.g. JPEG2k
        /// or TIFF layers.
        sizes?: Array<{
            width: number;
            height: number;
        }>;

        /// Tile sizes that are directly encoded in the image, e.g.
        /// JPEG2k or TIFF tiles.
        tileSizes?: Array<{
            width: number;
            height: number;
            scaleFactors: number[];
        }>;
    }

    /// Describes how the identifier was resolved
    interface ImageSource = (ResolvedImage | SourceNotModified) & {
        /// Optional caching information about the image source,
        /// will be set on the response headers and can be used
        /// by clients for subsequent requests to the same identifier
        cacheInfo?: {
            eTag?: string;
            lastModified?: Date;
        };
    }

    /// Finally, the resolve hook itself
    function resolve(
        identifier: string,
        clientETag?: string,
        clientLastModified?: string
    ): ImageSource | undefined;
    ```

=== "Python"
    ``` python
    # Size of an image (layer) in pixels
    class ImageSize(TypedDict):
        width: int
        height: int

    # Size of a tile encoded in an image, with available scaling factors
    class TileSize(TypedDict):
        width: int
        height: int
        scale_factors: list[int]

    # Optional set of metadata about the image that can be provided by the
    # resolver to avoid having Wolpi read the image just to extract
    # this information for `info.json` requests
    class ImageInfo(TypedDict):
        native_size: ImageSize
        sizes: NotRequired[list[ImageSize]]
        tile_sizes: NotRequired[list[TileSize]]

    # Optional caching information about the image source.
    class CacheInfo(TypedDict):
        e_tag: NotRequired[str]
        last_modified: NotRequired[datetime.datetime]

    # An image file in a file system accessible to Wolpi
    class FilesystemImageSource(TypedDict):
        path: str
        image_info: NotRequired[ImageInfo]
        cache_info: NotRequired[CacheInfo]

    # A data blob containing the raw (still encoded) image data,
    # will be decoded by libvips
    class BinaryImageSource(TypedDict):
        raw_data: bytes
        image_info: NotRequired[ImageInfo]
        cache_info: NotRequired[CacheInfo]

    # An image accessible via HTTP(S), optionally with custom request
    # headers (e.g. for auth) and a flag indicating whether byte-range 
    # requests are supported by the endpoint
    class HttpImageSource(TypedDict):
        url: str
        headers: NotRequired[dict[str, str]]
        supports_byte_range: NotRequired[bool]
        image_info: NotRequired[ImageInfo]
        cache_info: NotRequired[CacheInfo]

    # A custom data source that libvips will read from using the
    # provided callbacks, can be more efficient for reading very
    # large images from e.g. databases or object storage systems
    # that do not support HTTP with byte-range requests
    # This is best returned as an actual object, not a dict.
    class CustomImageSource(Protocol):

        # `whence` denotes the position to seek from: 0 (beginning of file),
        # 1 (current position) or 2 (end of file)
        def on_seek(self, offset: int, whence: int) -> int:
            ...

        # The returned buffer will be copied, feel free to reuse internal
        # buffers for subsequent calls
        def on_read(self, length: int) -> bytes | bytearray:
            ...

        def cache_info(self) -> CacheInfo | None:
            ...

        def image_info(self) -> ImageInfo | None:
            ...

    # Indicate that the source has not been modified since it was
    # last accessed by the client, determined from the caching headers
    # passed to the resolving hook
    class SourceNotModified(TypedDict):
        not_modified: Literal[True]

    type ImageSource = Union[
        FilesystemImageSource,
        BinaryImageSource,
        HttpImageSource,
        CustomImageSource,
        SourceNotModified
    ]

    def resolve(
        identifier: str,
        client_e_tag: str | None,
        client_last_modified: str | None
    ) -> ImageSource | None: ...
    ```

If there are multiple extensions that implement the `resolve` hook, Wolpi will call them in the order
they are configured until one of them returns a valid result.

### `augmentInfoJson` Hook

The `augmentInfoJson` hook is called when generating the `info.json` response for an image. The hook
receives the identifier and the current `info.json` object as parameters and can return a new object
with modifications or additions to the `info.json` response.

!!! warning "Return a **new** object from the hook!"

    The `augmentInfoJson` hook **must** return a **new** object with the modifications or additions
    to the `info.json` response. Modifying the passed-in object directly will cause a runtime error.

=== "JavaScript"
    ```typescript
    function augmentInfoJson(
        identifier: string,
        currentInfoJson: {[key: string]: any},
        iiifVersion: number,
    ): {[key: string]: any} {
        return {...currentInfoJson}; // (1)!
    }
    ```

    1.  **Do not** modify `currentInfoJson` directly, return a new object instead

=== "Python"
    ``` python
    def augment_info_json(
        identifier: str,
        current_info_json: dict[str, Any],
        iiif_version: int
    ) -> dict[str, Any]:
        return {**current_info_json}# (1)!
    ```

    1.  **Do not** modify `current_info_json` directly, return a new dict instead

If there are multiple extensions that implement the `augmentInfoJson` hook, Wolpi will call them
in the order they are configured, passing the result of each hook to the next one.

### `preProcessImage` Hook

The `preProcessImage` hook is called before any other image processing is done. The hook receives
a `VImage` object that gives access to libvips operations on the image, as well as metadata about
the image and the Image API request. It must return a new `VImage` object that represents the processed image,
or a `null`/`None` value to indicate that no processing was done (see [below][java-api]) for details on how
to interact with `VImage`).

[java-api]: #working-with-java-classes-from-extensions

=== "JavaScript"
    ```typescript
    function preProcessImage(
        identifier: string,
        // VImage is a Java host object, see below for details on how to work with it
        image: VImage,
        // see resolving hook types for ImageInfo type
        imageInfo: ImageInfo,
        request: {
            identifier: string;
            cropSpec: string;
            scaleSpec: string;
            rotationSpec: string;
            qualitySpec: string;
            formatSpec: string;
        }
    ): VImage | null;
    ```

=== "Python"
    ``` python
    class ImageApiRequest:
        identifier: str
        crop_spec: str
        scale_spec: str
        rotation_spec: str
        quality_spec: str
        format_spec: str

    def pre_process_image(
        identifier: str,
        # VImage is a Java host object, see below for details on how to work with it
        image: VImage,
        # see resolving hook types for ImageInfo type
        image_info: ImageInfo,
        request: ImageApiRequest
    ) -> VImage | None: ...
    ```

### Image Processing Hooks

With the `preProcess`, `preScale`, `preCrop`, `preRotate` and `preQuality` hooks,
extensions can implement custom behavior for the respective image operations. Each hook receives
the `VImage` at the current step of the processing pipeline, metadata about the image and the Image
API request. The hook must return a new `VImage` object that represents the processed image, or a
`null`/`None` value to indicate that no processing was done and Wolpi should continue with the
standard implementation (see [below][java-api]) for details on how to interact with `VImage`.

[java-api]: #working-with-java-classes-from-extensions

=== "JavaScript"
    ```typescript
    type ImageProcessingHook = (
        identifier: string,
        // VImage is a Java host object, see below for details on how to work with it
        image: VImage,
        // see resolving hook types for ImageInfo type
        imageInfo: ImageInfo,
        request: {
            identifier: string;
            cropSpec: string;
            scaleSpec: string;
            rotationSpec: string;
            qualitySpec: string;
            formatSpec: string;
        }
    ): VImage | null;

    const preProcessImage: ImageProcessingHook;
    const preScale: ImageProcessingHook;
    const preCrop: ImageProcessingHook;
    const preRotate: ImageProcessingHook;
    const preQuality: ImageProcessingHook;
    ```

=== "Python"
    ``` python
    class ImageApiRequest:
        identifier: str
        crop_spec: str
        scale_spec: str
        rotation_spec: str
        quality_spec: str
        format_spec: str

    type ImageProcessingHook = Callable[
        [
            str,             # identifier
            VImage,          # image
            ImageInfo,       # image_info
            ImageApiRequest  # request
        ],
        VImage | None
    ]

    pre_process_image: ImageProcessingHook
    pre_scale: ImageProcessingHook
    pre_crop: ImageProcessingHook
    pre_rotate: ImageProcessingHook
    pre_quality: ImageProcessingHook
    ```

If multiple extensions implement an image processing hook, Wolpi will call them in the order they are
configured and use the first non-null result as the processed image. If all extensions return
`null`/`None`, Wolpi will continue with the standard implementation for that operation.


### `preFormat` Hook

The `preFormat` hook is called before the image is encoded to the requested output format. The hook
has the same parameters as the above image processing hook, but instead of a `VImage` object, it
must return an `EncodedImage` object that represents the encoded image data, or a `null`/`None` value
to indicate that no encoding took place.

=== "JavaScript"
    ```typescript
    interface EncodedImage {
        data: Uint8Array;
        contentType: string;
        extraHeaders?: Record<string, string | string[]>;
    }

    function preForma(t
        identifier: string,
        image: VImage,
        imageInfo: ImageInfo,
        request: {
            identifier: string;
            cropSpec: string;
            scaleSpec: string;
            rotationSpec: string;
            qualitySpec: string;
            formatSpec: string;
        }
    ): EncodedImage | null;
    ```

=== "Python"
    ``` python
    class EncodedImage(TypedDict):
        data: bytes | bytearray
        content_type: str
        extra_headers: NotRequired[dict[str, str | list[str]]]

    def pre_format(
        identifier: str,
        image: VImage,
        image_info: ImageInfo,
        request: ImageApiRequest
    ) -> EncodedImage | None: ...
    ```

## The `wolpi` Object

Extensions have access to a `wolpi` object, which provides access to the Wolpi context. This
includes the extension's configuration, which can be accessed via `wolpi.config`. In JavaScript,
this object is directly available in the global scope, while in Python, it is available as a
module `wolpi` that can be imported.


=== "JavaScript"

    ```typescript
    interface WolpiContext {  // (1)!
        // Configuration object/dict for the extension, if present
        config: Record<string, any> | undefined;
        // Version specifier for Wolpi
        wolpiVersion: string;
        // Version specifier for the extension
        extensionVersion: string;
        // Logger instance for logging messages
        logger: WolpiLogger;
        // Metrics object to register custom metrics
        metrics: WolpiMetrics;
        // A memory arena that can be passed to vips-ffm APIs for creating
        // new images, should be treated as an opaque handle that is only
        // forwarded to vips-ffm, __do not__ store a reference to it or try to
        // manipulate it directly
        vipsArena: unknown;
        // This objects provides parsing methods for all parts of the IIIF
        // Image API request, use this if you want to implement custom behavior
        // based on the official syntax
        imageRequestParser: ImageRequestParser;
    }

    interface WolpiLogger {
        debug(message: string, keyVals?: {[key: string]: string}): void;
        info(message: string, keyVals?: {[key: string]: string}): void;
        warn(message: string, keyVals?: {[key: string]: string}): void;
        error(message: string, keyVals?: {[key: string]: string}): void;
    }

    interface WolpiMetrics {
      counter(
          name: string, description?: string, unit?: string,
          labels?: { [key: string]: string }
      ): { increment(value?: number): void },

      gauge(
          name: string, description?: string, unit?: string,
          labels?: { [key: string]: string }
      ): { set(value: number): void },

      timer(
          name: string, description?: string,
          labels?: { [key: string]: string }
      ): {
        record(fn: () => void): void,
        start(): { stop(): void }
      }
    }

    interface ImageRequestParser {
        parseRegion(spec: string, sourceSize: ImageSize): {
            x: number;
            y: number;
            width: number;
            height: number;
        };
        parseSize(version: 'v2' | 'v3', spec: string, sourceSize: ImageSize): {
            width: number;
            height: number;
        };
        parseRotation(spec: string): {
            degrees: number;
            mirror: boolean;
        };
        parseQuality(spec: string): 'color' | 'gray' | 'bitonal';
    }

    ```

    1.  In JavaScript, the `wolpi` object of type `WolpiContext` is
        available in the global scope for all extensions.

=== "Python"

    ``` python
    class WolpiContext:# (1)!
        # Configuration object/dict for the extension, if present
        config: dict[str, Any] | None
        # Version specifier for Wolpi
        wolpiVersion: str
        # Version specifier for the extension
        extensionVersion: str
        # Logger instance for logging messages
        logger: WolpiLogger
        # Metrics object to register custom metrics
        metrics: WolpiMetrics
        # A memory arena that can be passed to vips-ffm APIs for creating
        # new images, should be treated as an opaque handle that is only
        # forwarded to vips-ffm, __do not__ store a reference to it or try to
        # manipulate it directly
        vipsArena: object
        # This objects provides parsing methods for all parts of the IIIF
        # Image API request, use this if you want to implement custom behavior
        # based on the official syntax
        imageRequestParser: ImageRequestParser

    class WolpiLogger:
        def debug(self, message: str, keyVals: dict[str, str] | None) -> None: ...
        def info(self, message: str, keyVals: dict[str, str] | None) -> None: ...
        def warn(self, message: str, keyVals: dict[str, str] | None) -> None: ...
        def error(self, message: str, keyVals: dict[str, str] | None) -> None: ...

    class WolpiMetrics:
      def counter(
          self,
          name: str, description: str | None, unit: str | None,
          labels: dict[str, str]
      ) -> CounterMetric: ...

      def gauge(
          self,
          name: str, description: str | None, unit: str | None,
          labels: dict[str, str]
      ) -> GaugeMetric: ...

      def timer(
          self,
          name: str, description: str | None, labels: dict[str, str]
      ) -> TimerMetric: ...

    class CounterMetric:
      def increment(self, value: int | None = None) -> None: ...

    class GaugeMetric:
      def set(self, value: int) -> None: ...

    class RunningTimer:
      def stop(self) -> None: ...

    class TimerMetric:
      def record(self, fn: Callable[[], None]) -> None: ...
      def start(self) -> RunningTimer: ...

    class ImageRequestParser:
        def parseRegion(self, spec: str, source_size: ImageSize) -> CropRegion: ...
        def parseSize(self, version: Literal['v2', 'v3'], spec: str, source_size: ImageSize) -> ImageSize: ...
        def parseRotation(self, spec: str) -> Rotation: ...
        def parseQuality(self, spec: str) -> Literal['color', 'gray', 'bitonal']: ...

    class CropRegion:
        x: int
        y: int
        width: int
        height: int

    class ImageSize:
        width: int
        height: int

    class Rotation:
        degrees: int
        mirror: bool
    ```

    1.  In Python, the `wolpi` object of type `WolpiContext` is available as a module that can be
        imported:
        ``` python
        from wolpi import wolpi
        ```

## Extension Lifecycle

Extensions in Wolpi are kept in a pool after they have been loaded, so that they can be reused for
multiple subsequent requests without having to run expensive initialization code for each request.
Wolpi ensures that **only one request is processed by any one extension instance at a time**, so that
extensions do not need to worry about concurrency issues and can keep state in memory between
hook invocations and requests without having to secure it with locks or other synchronization
mechanisms. This also means that developers can safely assume that the hooks are always called
in a specific order:

- `info.json` requests: `authorize` → `resolve` → `augmentInfoJson`
- Image request: `authorize` → `resolve` → `preProcessImage` → `preCrop` → `preScale`
                  → `preRotate` → `preQuality` → `preFormat` → `cleanup`

This means that you can maintain state between hook invocations and can be sure that the state
will always refer to the same request, as long as you clean it up in the end:
Wolpi requires that every extension implements a `cleanup` hook, which is called after the response
has been sent to the client. Use this hook to clear up any state that should not persist between
requests. It's perfectly fine to have the hook do nothing if your extension does not accumulate any
request-scoped state, but we mandate it anyway to avoid accidental state leaks (which are really
difficult to debug).


## Extension Configuration

Extensions are configured in the `application.yml` file. Each extension has a description of
its source (`path`, `npm`, or `pypi`) and can optionally have a set of configuration options.

**Example:**

```yaml linenums="1"
extensions:
  - path: /path/to/helloworld.js
    config:
      baseDirectory: /path/to/images
  - npm:
      pkg: "hello-js-package"
      version: "1.0.0"
      # index: "https://custom-registry.example.com"
  - pypi:
      pkg: "hello-py-package"
      version: "1.0.0"
      # index: "https://custom-pypi.example.com/simple"
    config:
      apiUrl: "https://api.example.com"
```

## Behavior when multiple extensions implement the same hook

When configuring multiple extensions, it can happen that more than one extension implements
the same hook. What happens in this case depends on the hook:

### `authorize`
Called **in parallel** until one returns `false`, in which case the request is
considered unauthorized and all other pending hook calls are canceled. If all return `true`,
the request is authorized. If any of the extensions throws an error, the request fails with a
error response.

### `resolve`
Called **in parallel** until one resolves to a valid image source, in which case all other
pending hook calls are canceled. If none resolve the identifier, the request fails with
a `404 Not Found` error. If any of the extensions throws an error and none of the others can
resolve the identifier, the request fails with an error response, otherwise the error is logged
and the first successful resolution is used.

!!! warning "Order is not guaranteed!"

    Since the `authorize` and `resolve` hooks are called in parallel, there is no guarantee
    about the order in which the extensions are called. If you have multiple extensions that
    implement these hooks, ensure that they do not depend on being called in a specific order and
    ideally that there is no overlap in the identifiers they can handle.
    If you need ordered behavior, e.g. to implement a custom "fallback resolver", consider combining
    the logic into a single extension by declaring the other extensions as dependencies in your own
    extension package, assuming they share a programming language.

### `augmentInfoJson` and `preProcessImage`
Called in sequence, passing the result of each hook as the input to the next one. If any of them
throw an error, the request fails with an error response.

### `preCrop`, `preScale`, `preRotate`, `preQuality`, `preFormat`
Called in sequence until one returns a non-null result. If none returns a non-null result,
the standard implementation for that operation is used. If any of them throw an error,
the request fails with an error response.

## JavaScript Extensions

Wolpi uses [GraalVM's JavaScript runtime][graaljs] to run JavaScript extensions. This runtime
is fully compliant with the ECMAScript 2024 specification and supports most modern JavaScript
features (including `async`/`await`, though see the note below about asynchronous hooks). For more
details, refer to the [GraalJS documentation][graaljs-docs].

Note, however, that the runtime does not provide Node.js or browser-specific APIs, which limits
interoperability with a large chunk of the npm ecosystem. However, Wolpi provides [a few synchronous
Wolpi-specific polyfills][polyfills] for filesystem and HTTP APIs for extension authors.

JavaScript extensions must be written as ES modules with either:

- a default export with the hooks as entries in an object
- or named exports for each hook.

!!! question "Async?"

    Currently Wolpi does not support asynchronous hooks in JavaScript extensions. All hooks must be
    synchronous functions/methods. This is not as big of an issue as it might seem, since there are
    neither browser nor Node.js APIs available and the Java host does not have an async event loop.
    If you do have a use case for asynchronous behavior that we might not have been aware of, please
    open an issue on the Wolpi GitHub repository and we may consider it for a future iteration of
    the extension API.

[graaljs]: https://www.graalvm.org/latest/reference-manual/js/
[polyfills]: #wolpi-modules
[graaljs-docs]: https://www.graalvm.org/latest/reference-manual/js/JavaScriptCompatibility

### `wolpi:` Modules

Wolpi provides a few built-in polyfill modules that can be imported by JavaScript extensions:

#### `wolpi:fs`

Provides a subset of the Node.js `fs` module for synchronous file system operations

```typescript
/// Reads the entire contents of a file into a `Uint8Array`.
function readFileSync(path: string): Uint8Array;

interface DirEnt {
    name: string;
    parentPath: string;
    isFile(): boolean;
    isDirectory(): boolean;
}

/// Reads the contents of a directory
function readDirSync(path: string): DirEnt[]

interface Stats {
  isFile(): boolean;
  isDirectory(): boolean;
  size: number;
  mtimeMs: number;
}

/// Provides basic file metadata
function statSync(path: string): Stats;

/// Checks if the file or directory at `path` is accessible with the given mode
/// (default: `'r'` for read access, other values include `w` and `x` and
/// combinations thereof). Returns `true` if accessible, `false` otherwise.
function accessSync(path: string, mode?: string): boolean;
```

#### `wolpi:fetch`

Provides a synchronous `fetch` function for making HTTP requests:

```typescript
interface FetchResponse {
    arrayBuffer(): Uint8Array;
    text(): string;
    json(): any;
    status: number;
    statusText: string;
    headers: Record<string, string>;
}

/// Makes a synchronous HTTP request to the given URL with the specified options.
function fetchSync(
    url: string,
    options?: {
      body?: string | Uint8Array,
      method?: string,
      headers?: Record<string, string>,
   }): FetchResponse
```

## Python Extensions

The Python runtime used in Wolpi is [GraalPy][graalpy], which is a fully compliant Python 3.12
runtime. Python extensions have full access to all parts of the Python 3.12 standard library, including
things like `urllib.request` for making HTTP requests, `os`/`pathlib` for file system access and
even `sqlite3` for local databases.

Python Extensions can make use of dependencies, including dependencies with native code. However,
note that not all packages on PyPI are supported by the GraalPy runtime. A good source to check for
compatibility is the registry of compatible packages [available from the GraalPy website][graalpy-compat].
Note that many packages that are noted as passing less than 100% of their test suite might still work
fine for your use case; it's usually worth the time to evaluate a dependency using a GraalPy standalone REPL.

[graalpy]: https://www.graalvm.org/python
[graalpy-compat]: https://www.graalvm.org/python/compatibility/

!!! warning "Native Extensions"

    If the notes in the table on the compatibility page mention patches being applied by GraalPy, 
    you need to install GraalPy locally and set the `wolpi.packaging.python-executable` to the path
    to your GraalPy Python executable. This ensures that any necessary patches are applied when
    installing the extension package. By default, your system's default Python 3 interpreter is used.
    **If possible, use the container, it has everything needed included by default.**


## Single-File Extensions

=== "JavaScript"
    A single-file JavaScript extension is a single `.js` file that exports its hooks.

    ```javascript linenums="1" title="helloworld.js"
    // helloworld.js
    export default {
      info: () => ({
        apiVersion: 1,
        name: 'hello-world',
        description: 'just a simple resolving proof-of-concept'
      }),
      cleanup: () => {
        // no cleanup needed, but method must be present
      },
      resolve: (identifier) => {
        if (!identifier.startsWith('js-')) {
          return;
        }
        // wolpi.config has the configuration object for this extension
        const { baseDirectory } = wolpi.config;
        return {
          path: `${baseDirectory}/${identifier.substring(3)}.jp2`
        }
        return {
          url: `https://${cdnBaseUrl}/${identifier.substring(3)}/image.jp2`
        }
      }
    }
    ```

=== "Python"
    A single-file Python extension is a single `.py` file that defines its hooks as top-level functions.

    ```python linenums="1" title="helloworld.py"
    from pathlib import Path

    import wolpi

    IMAGE_EXTENSIONS = {'.jpg', '.jpeg', '.png', '.gif', '.jp2', '.tif', '.webp'}


    def info():
      return {
        'apiVersion': 1,
        'name': 'hello-world-py',
        'description': 'just a simple resolving proof-of-concept'
      }

    def cleanup():
      # no cleanup needed, but method must be present
      pass


    def resolve(identifier):
      if not identifier.startswith('py-'):
        return
      identifier = identifier[3:]
      base_dir = Path(wolpi.config["baseDirectory"])
      for path in base_dir.iterdir():
        if path.stem == identifier and path.suffix in IMAGE_EXTENSIONS:
          return {'path': str(path.absolute())}
    ```

## Package-Based Extensions

=== "JavaScript"

    A JavaScript extension can also be a standard npm package. The `package.json` file must have an
    `exports` field that points to the entry point of the extension. The entry point must export the
    hooks in the same way as a single-file extension. The package can either be local or published to
    npm or another registry.

    The package can declare dependencies. **Note that the Wolpi JavaScript runtime provides neither Node
    nor Browser-specific APIs**, which limits the set of usable packages to those that do not depend on
    APIs from either.

    ```json5 linenums="1" title="package.json"
    {
      "name": "hello-js-package",
      "version": "1.0.0",
      "description": "A package-based JavaScript extension for Wolpi.",
      // Entry point for the extension, should follow the same export conventions
      // as single-file extensions
      "exports": "./index.js"
    }
    ```

=== "Python"
    A Python extension can also be a standard Python package, either in a local directory or from a
    package registry (defaults to PyPI, but custom indices can be configured). The package must define
    a `wolpi` entry point in its `pyproject.toml`. This entry point must point to a callable that
    returns a dictionary of hooks, or an object that has the hooks as methods.

    ```toml linenums="1" title="./pyproject.toml"
    [project]
    name = "hello-py-package"
    version = "1.0.0"
    description = "A package-based Python extension for Wolpi."
    dependencies = [
        "redis"# (1)!
    ]

    [project.entry-points.wolpi]
    extension = "hello_py_pkg.extension:extension"# (2)!
    ```

    1.  We depend on the `redis` package from PyPI. The GraalPy compatibility table states that the
        test suite only passes 65% of the tests, but most of the basic functionality works fine.
        It's usually worth spinning up a standalone GraalPy interpreter and playing around with a
        package to see if it's working as intended.
    2.  This part is crucial for a packaged Python extension: We define a `wolpi` entry point
        that points to the `extension` callable in the `hello_py_pkg.extension` module. This callable
        must return an object with the extension hooks as methods, or a dictionary with the hooks
        as entries.

    ```python linenums="1" title="./src/hello_py_pkg/extension.py" hl_lines="7-13 25 33 43-44"
    import redis

    import wolpi

    class SampleExtension:# (1)!
      def __init__(self):
        self.redis_client = redis.Redis(# (2)!
            host=wolpi.config.get('redisHost', 'localhost'),
            port=wolpi.config.get('redisPort', 6379))
        self.log = wolpi.logger
        self.internal_user_counter = wolpi.metrics.counter(
            'sample_ext_internal_user_requests_total')
        self.current_request_is_internal_user = False

        def info(self):
          return {
            'apiVersion': 1,
            'name': ('A small extension that performs auth based on '
                     'a Redis set and a HTTP header'),
          }

        def authorize(self, identifier, headers, client_ip):
          # Internal users get a free pass and more features
          if headers.get('X-Is-Internal-Users') == 'true':
            self.current_request_is_internal_user = True# (3)!
            self.internal_user_counter.increment()
            return True
          # Simple authorization check against Redis for everybody else
          return self.redis_client.sismember('allowed_identifiers', identifier)

        def pre_scale(self, identifier, image, image_info, request):
          if (
              self.current_request_is_internal_user# (4)!
              and request.scale_spec in ('full', 'max')):
            # Internal users can request full-resolution images even if the
            # limits might be lower than the native resolution
            self.logger.info(
                f'Internal user request for {identifier}, skipping scaling')
            return image
          # proceed with normal scaling for everybody else
          return None
        
        def cleanup(self):
          self.current_request_is_internal_user = False# (5)!


    def extension():
      return SampleExtension()
    ```

    1.  Since we maintain a bunch of state in this extension, it's a good idea to use a class to
        hold the state and the extension behavior together.
    2. Our state consists of:
        - A Redis client that we use to do our lookup in the auth set. This client will live for as long
          as the extension object lives, i.e. across many requests
        - A counter metric that we use to monitor how many internal clients were requesting
          full-resolution images. We could also have recreated this metric during every request
          (metrics are cached, i.e. this would have been cheap), but it's cleaner to keep it around
        - A reference to our extension logger so we can see what's going on in the logs
        - And finally: a **request-scoped** state variable. The hooks in this extension are called in
          order, and one instance is only ever used for a single request at a time, i.e. it is safe
          to set up a state variable at any point in the pipeline and then refer to it from a later
          point, as long as we don't forget to clean up after every request with the `cleanup` hook
    3.  We set our request-scoped state variable in the `authorize` hook, where we have access to the
        client request's HTTP headers, so we can know later on if the user identified as an internal
        user.
    4.  Here we check the request-scoped state variable that we have previously set in `authorize`,
        since we no longer have access to the HTTP headers at this point
    5.  And finally, we must reset the state variable so the next request gets a clean state

## Error Handling in Extensions

By default, any exception that is raised in an extension hook will be logged along with a (filtered
to reduce noise) stack trace. Depending on the hook where the exception occurred, Wolpi will either
return a HTTP 500 response to the client, or skip the extension and continue processing with the next
extension (if multiple extensions are configured).

If you need to return a HTTP error response to the client from within an extension hook, you can
raise a special exception from your extension hooks:

=== "JavaScript"
    ```typescript
    type HttpStatusError = {
      message: string;
      status: number;
      details?: {[key: string]: any};
    }

    export default {
      // info/cleanup omitted for brevity
      resolve: (identifier) => {
        if (identifier === 'broken-identifier') {
          throw {
            "message": "Got a broken identifier",
            "status": 400,
            "details": {
              "cause": "identifier had 'broken' in it."
            }
          }
        }
      }
      // ...
    }
    ```
=== "Python"
    ```python title="wolpi.errors"
    class HttpStatusError(Exception):
        """
        Exception to return a HTTP response with a status code, a message and an
        optional JSON body.
        """

        #: Error message associated with the exception
        message: str

        #: HTTP status code associated with the exception
        status: int

        #: Optional JSON body to include in the response
        details: dict | None

        def __init__(self, message: str, status: int, details: dict | None = None):
            self.message = message
            self.status = status
            self.details = details
            super().__init__(message)
    ```

    ``` python
    from wolpi.errors import HttpStatusError

    def resolve(identifier: str) -> ImageSource:
      if identifier == 'broken-identifier':
        raise HttpStatusError(
          message='Got a broken identifier',
          status=400,
          details={
            'cause': "identifier had 'broken' in it."
          }
        )
    ```

If  Wolpi encounters such an exception, it will return a corresponding HTTP response to the client,
except for the `resolve` hook, where it will skip the extension and continue processing with the
other configured resolving extensions. Only if no other extension is able to resolve the identifier,
Wolpi will return the error response from the first extension that raised such an exception.
Otherwise, the exception is logged and Wolpi continues processing with the result of the other
successful extension.

## Developing Extensions

### Setup

We highly recommend using the official container image for developing extensions, since it already ships
with all the dependencies required to run Wolpi with Python/JavaScript extensions (including those with
third party dependencies). To do so, simply mount your configuration and the directory with your extensions
into the container:

```bash
$ docker run \
    -p 8080:8080 \
    -p 4711:4711 \ # (1)!
    -v config.yml:/app/wolpi.yml \ # (2)!
    -v ./my-extensions:/app/extensions \
    ghcr.io/dbmdz/wolpi:latest
```

1. For debugging, see [below](#debugging-extensions)
2. You can customize the configuration path inside the container by setting the `WOLPI_CONFIG`
   environment variable, by default Wolpi will check `/app/wolpi.yml` or `/app/wolpi.yaml`

Your `config.yml` should specify the absolute path to the extension in the container:

```yaml
extensions:
  - path: /app/extensions/hello-world.js
```

### Live Reload for local extensions
To make development easier, Wolpi supports installing local extensions with live reload enabled.
This allows you to make changes to your extension code and have them automatically picked up by Wolpi
without having to restart the entire Wolpi application.

Simply set the `live-reload: true` option in the extension definition in `application.yml`:

```yaml linenums="1"
extensions:
  - path: /path/to/your/extension.js
    live-reload: true
```

This feature comes with some caveats:

- Only changes to the *source files* are picked up. If you make modifications to your
`package.json` or `pyproject.toml`, you will have to restart Wolpi to have them take effect.
- If you want live reload for single-file extensions mounted into a container,
  you must mount the parent directory of the extension into the container, and
  not just the file itself to get live reloading to work

=== "JavaScript"
    - The `npm` executable used must be at least version 10.

=== "Python"
    - You need to have a standalone GraalPy installation for this to work and configure
    Wolpi to use it for installing Python extensions, either by putting the `graalpy` executable on
    your `$PATH` or by setting the `wolpi.packaging.python-executable` configuration option. The
    major version of GraalPy must match the one used in Wolpi (currently: **25**).

The official Wolpi container image already comes with both `npm` and `GraalPy` installed, so you can
use that as a base for your development environment if needed. For easy local installation of these
dependencies, we recommend using a tool like [mise][mise].

[mise]: https://mise.jdx.dev/

### Debugging Extensions
Wolpi provides support for the "Advanced Debugging Protocol" (ADP), which allows you to connect a
debugger to it and set breakpoints, step through code, and inspect variables inside your extensions.

To enable debugging, set the `wolpi.extension-debug` section in your config:

```yaml linenums="1" title="wolpi.yml"
extension-debug:
  # Enable or disable extension debugging, global setting for
  # all extensions and languages
  enabled: true
  # Host and port to listen on for debugger connections
  host: localhost
  port: 4711
  # Suspend execution at first source line
  suspend: false
  # Only begin executing after a debugger has connected
  waitAttached: false
```

Then, open the directory with your extensions in Visual Studio Code and create the following
launch configuration in `.vscode/launch.json`:

```json linenums="1" title=".vscode/launch.json"
{
    "version": "0.2.0",
    "configurations": [
        {
            "name": "Attach to JS Extensions",
            "debugServer": 4711,
            "request": "attach",
            "type": "node"
        },
        {
            "name": "Attach to Python Extensions",
            "debugServer": 4711,
            "request": "attach",
            "type": "debugpy"
        }
    ]
}
```

Start up Wolpi, then start the debugger in VS Code using the "Attach to JS Extensions" or
"Attach to Python Extensions" configuration. You should see the application threads and be able to
set breakpoints and step through your extension code.

!!! warning "Debugging with the Wolpi container"

    Debugging extensions with Wolpi running in a container has some caveats:

    - `live-reload: true` must be set for the extension in the config
    - Breakpoints only work if the path to the extension inside the container matches the path
      on the host machine. To achieve this, mount the directory with your extensions into the
      container at the same absolute path as on your host system. This is a limit of the Graal Debug
      Adapter implementation that does not allow remapping paths at the moment.

## Working with Java classes from extensions

Extensions have free access to all Java classes on the Wolpi classpath and can use them as needed
to implement their functionality. To do so, use the *Graal Polyglot API* ([Python][graal-polyglot-py]/[JavaScript][graal-polyglot-js]) to
get a reference to a Java class and then call its static methods or create instances as needed.

=== "JavaScript"
    ```typescript linenums="1"
    const System = Java.type('java.lang.System');
    System.out.println('Hello from JavaScript!');
    ```

=== "Python"
    ``` python linenums="1"
    import java

    System = java.type('java.lang.System')
    System.out.println('Hello from Python!')
    ```

Image hooks also receive a ready-made Java object instance for a [`VImage`][vimage-javadoc] object
that they can call  APIs on to perform image processing. This class comes from the
[`vips-ffm`][vips-ffm] Java bindings for libvips, refer to the [JavaDoc][vips-ffm-javadoc] to learn
more about the available APIs for image processing.

[graal-polyglot-py]: https://www.graalvm.org/python/docs/#interoperability
[graal-polyglot-js]: https://www.graalvm.org/latest/reference-manual/js/JavaInteroperability/#access-java-from-javascript
[vimage-javadoc]: https://vipsffm.photofox.app/app.photofox.vipsffm/app/photofox/vipsffm/VImage.html
[vips-ffm]: https://github.com/lopcode/vips-ffm
[vips-ffm-javadoc]: https://vipsffm.photofox.app/

=== "JavaScript"
    ```typescript linenums="1"
    // Needed to create an overlay image, **not** to simply use the APIs on
    // the existing `VImage` object passed to the hook
    const VImage = Java.type('app.photofox.vipsffm.VImage');

    function preProcessImage(vimage, identifier, info, request) {
        // Call VImage methods on the vimage object
        const width = vimage.width();
        const height = vimage.height();
        wolpi.logger.info(
            `Processing image ${identifier} with dimensions ${width}x${height}`);

        // Draw the identifier as a watermark in the bottom-right corner
        const watermarkText = VImage.text(wolpi.vipsArena, identifier);// (1)!
        return vimage.insert(
            watermarkText,
            width - watermarkText.width() - 10,
            height - watermarkText.height() - 10
        );
    }
    ```

    1.  Here we create a new `VImage` object with some text to use as a watermark.
        We need to pass the `wolpi.vipsArena` memory arena to the [`VImage.text`][text]
        method to create the image, since the vips bindings we use need to do an allocation 
        for that.

    [text]: https://vipsffm.photofox.app/app.photofox.vipsffm/app/photofox/vipsffm/VImage.html#text(java.lang.foreign.Arena,java.lang.String,app.photofox.vipsffm.VipsOption...)

=== "Python"
    ``` python linenums="1"
    import java
    import wolpi

    # Needed to create an overlay image, **not** to simply use the APIs on
    # the existing `VImage` object passed to the hook
    VImage = java.type('app.photofox.vipsffm.VImage')

    def pre_process_image(vimage, identifier, info, request):
        # Call VImage methods on the vimage object
        width = vimage.width()
        height = vimage.height()
        wolpi.logger.info(
            f"Processing image {identifier} with dimensions {width}x{height}")

        # Draw the identifier as a watermark in the bottom-right corner
        watermark_text = VImage.text(wolpi.vipsArena, identifier)# (1)!
        return vimage.insert(
            watermark_text,
            width - watermark_text.width() - 10,
            height - watermark_text.height() - 10
        )
    ```

    1.  Here we create a new `VImage` object with some text to use as a watermark.
        We need to pass the `wolpi.vipsArena` memory arena to the [`VImage.text`][text]
        method to create the image, since the vips bindings we use need to do an allocation 
        for that.

    [text]: https://vipsffm.photofox.app/app.photofox.vipsffm/app/photofox/vipsffm/VImage.html#text(java.lang.foreign.Arena,java.lang.String,app.photofox.vipsffm.VipsOption...)
