# Wolpi Extensions

Wolpi's functionality can be extended with custom logic written in JavaScript or Python. This allows
for integration with various authentication providers and image sources and as well as customizing
the standard processing pipelines with extra syntax or entirely custom behavior.

## General Concepts

### Hooks

Wolpi extensions work by implementing one or more "hooks". A hook is a function that is called by
Wolpi at a specific point in its processing pipeline. The available hooks are:

- `info`: Returns metadata about the extension. **This hook is required for all extensions.**
- `resolve`: Resolves an identifier to an image source (e.g., a file path or a URL).
- `authorize`: Authorizes a request before any processing is done.
- `preProcessImage`: Performs custom image processing before any other transformations.
- `preScale`: Implements custom scaling behavior and parameter parsing, optionally bypassing the
   standard implementation
- `preCrop`: Implements custom cropping behavior and parameter parsing, optionally bypassing the
  standard implementation
- `preRotate`: Implements custom rotation behavior and parameter parsing, optionally bypassing the
  standard implementation
- `preQuality`: Implements custom quality adjustment behavior and parameter parsing, optionally
  bypassing
  the standard implementation
- `preFormat`: Implements custom format conversion behavior and parameter parsing, optionally
  bypassing the standard implementation

### The `info` Hook

Every extension must implement the `info` hook. This hook returns a JSON object with the following
fields:

- `apiVersion`: The version of the Wolpi extension API used by the extension. Currently, this should
  be `1`.
- `name`: The name of the extension.
- `description`: A brief description of what the extension does.

### The `cleanup` Hook

Every extension **must** implement the `cleanup` hook, even if it does nothing. This hook is called
after the response has been sent to the client and must be used to clean up any state that was
accumulated during the processing of a request and should not persist between requests.

### The `wolpi` Global

Extensions have access to a `wolpi` global object, which provides access to the Wolpi context. This
includes the extension's configuration, which can be accessed via `wolpi.config()`.

```typescript
interface WolpiContext {
    // Configuration object/dict for the extension, if present
    config: () => Record<string, any> | undefined;
    // Version specifier for Wolpi
    wolpiVersion: () => string;
    // Version specifier for the extension
    extensionVersion: () => string;
}
```

### Extension Lifecycle

Extensions in Wolpi are kept in a pool after they have been loaded, so that they can be reused for
multiple subsequent requests without having to run expensive initialization code for each request.
Wolpi ensures that only one requests is processed by any one extension instance at a time, so that
extensions do not need to worry about concurrency issues and can keep state in memory between
hook invocations and requests without having to secure it with locks or other synchronization
mechanisms.

However, this makes it easy to shoot yourself in the foot by not clearing up request-specific state
after a request has been processed. To avoid this, Wolpi requires that every extension implements
a `cleanup` hook, which is called after the response has been sent to the client. Use this hook
to clear up any state that should not persist between requests. It's perfectly fine to have the
hook do nothing if your extension does not accumulate any state, but we mandate it anyway to avoid
accidental state leaks.


## Configuration

Extensions are configured in the `application.yml` file. Each extension has a type (`path`, `npm`,
or `pypi`) and a set of configuration options.

**Example:**

```yaml
wolpi:
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

## Examples

For more detailed examples than the ones below, see the `samples` directory in the Wolpi project.

## JavaScript Extensions

JavaScript extensions must be written as ES modules with either a default export with the hooks
as entries in an object, or named exports for each hook. The JavaScript runtime does not provide
Node.js or Browser-specific APIs, but a few synchronous Wolpi-specific polyfills are available
(see below).

### Single-File Extensions

A single-file JavaScript extension is a single `.js` file that exports its hooks. 

**Example:**

```javascript
// helloworld.js
export default {
  info: () => ({
    apiVersion: 1,
    name: 'hello-world',
    description: 'just a simple resolving proof-of-concept'
  }),
  resolve: (identifier) => {
    if (!identifier.startsWith('js-')) {
      return;
    }
    // wolpi.config() returns the configuration object for this extension
    const { baseDirectory } = wolpi.config();
    return {
      path: `${baseDirectory}/${identifier.substring(3)}.jp2`
    }
    return {
      url: `https://${cdnBaseUrl}/${identifier.substring(3)}/image.jp2`
    }
  }
}
```

### Package-Based Extensions

A JavaScript extension can also be a standard npm package. The `package.json` file must have an
`exports` field that points to the entry point of the extension. The entry point must export the
hooks in the same way as a single-file extension. The package can either be local or published to
npm or another registry.

The package can declare dependencies. **Note that the Wolpi JavaScript runtime provides neither Node
nor Browser-specific APIs**, which limits the set of usable packages to those that do not depend on
APIs from either.

**Example:**

```json5
// package.json
{
  "name": "hello-js-package",
  "version": "1.0.0",
  "description": "A package-based JavaScript extension for Wolpi.",
  // Entry point for the extension, should follow the same export conventions as single-file extensions
  "exports": "./index.js"
}
```

### `wolpi:` Modules

Wolpi provides a few built-in polyfill modules that can be imported by JavaScript extensions:

- `wolpi:fs`: Provides a subset of the Node.js `fs` module for synchronous file system operations:
  - `readFileSync(path: string): Uint8Array`: Reads the entire contents of a file into a `Uint8Array`.
  - `readDirSync(path: string): DirEnt[]`: Reads the contents of a directory as an array of `DirEnt`
    objects that provide the `name`, `parentPath` and `isFile()`/`isDirectory()` methods.
  - `statSync(path: string): Stats`: Provides basic file metadata, including `isFile()`,
    `isDirectory()`, `size`, and `mtimeMs`.
  - `accessSync(path: string, mode?: string): boolean`: Checks if the file or directory at `path`
    is accessible with the given mode (default: `'r'` for read access, other values include `w` and
    `x` and combinations thereof). Returns `true` if accessible, `false` otherwise.
- `wolpi:fetch`: Provides a synchronous `fetch` function for making HTTP requests:
  - `fetch(url, { body?: string | Uint8Array, method?: string, headers?: Record<string, string> }?): FetchResponse`:
    Makes a synchronous HTTP request to the given URL with the specified options. Returns a
    `FetchResponse` object with the following methods:
    - `arrayBuffer(): Uint8Array`: Returns the response body as a `Uint8Array`.
    - `text(): string`: Returns the response body as a string.
    - `json(): any`: Parses the response body as JSON and returns the resulting object.
    - `status`: The HTTP status code of the response.
    - `statusText`: The HTTP status text of the response.
    - `headers`: A `Record<string, string>` of the response headers.

## Python Extensions

### Single-File Extensions

A single-file Python extension is a single `.py` file that defines its hooks as top-level functions.
Extensions have full access to all parts of the Python 3.11 standard library, including things like
`urllib.request` for making HTTP requests, `os`/`pathlib` for file system access and even `sqlite3`
for local databases. However, note that the Wolpi Python runtime is based on GraalPy, which may
limit compatibility with some packages that use native code.

**Example:**

```python
# helloworld.py
from pathlib import Path

IMAGE_EXTENSIONS = {'.jpg', '.jpeg', '.png', '.gif', '.jp2', '.tif', '.webp'}


def info():
  return {
    'apiVersion': 1,
    'name': 'hello-world-py',
    'description': 'just a simple resolving proof-of-concept'
  }


def resolve(identifier):
  if not identifier.startswith('py-'):
    return
  identifier = identifier[3:]
  base_dir = Path(wolpi.config()['baseDirectory'])
  for path in base_dir.iterdir():
    if path.stem == identifier and path.suffix in IMAGE_EXTENSIONS:
      return {'path': str(path.absolute())}
```

### Package-Based Extensions

A Python extension can also be a standard Python package, either in a local directory or from a
package registriy (defaults to PyPI, but custom indices can be configured). The package must define
a `wolpi` entry  point in its `pyproject.toml`. This entry point must point to a callable that
returns  a dictionary of hooks, or an object that has the hooks as methods.

Python Extensions can make use of dependencies, including dependencies with native code. However,
note that not all packages on PyPI are supported by the GraalPy runtime. A good source to check for
compatibility is available at https://www.graalvm.org/python/compatibility/

**If the notes in the table on the compatibility page mention patches being applied by GraalPy**, 
you need to install GraalPy locally and set the `wolpi.packaging.python-executable` to the path
to your GraalPy Python executable. This ensures that any necessary patches are applied when
installing the extension package. By default, your system's default Python 3 interpreter is  used.

**Example:**

```toml
# pyproject.toml
[project]
name = "hello-py-package"
version = "1.0.0"
description = "A package-based Python extension for Wolpi."
dependencies = [
    "requests"
]

[project.entry-points.wolpi]
extension = "hello_py_pkg.extension:extension"
```

```python
# src/hello_py_pkg/extension.py
import requests

def info():
  return {
    'apiVersion': 1,
    'name': 'wikimedia-resolver',
    'description': 'Resolves images from Wikimedia Commons.'
  }


def resolve(identifier):
  api_url = wolpi.config()['apiUrl']
  resp = requests.get(f"{api_url}/resolve/{identifier}")
  if resp.status_code != 200:
    return
  image_url = resp.json()['url']
  return {
    'url': image_url
  }


def extension():
  return {
    'info': info,
    'resolve': resolve
  }
```
