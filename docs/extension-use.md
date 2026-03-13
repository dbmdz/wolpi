# Using Extensions

Wolpi's functionality can be extended with custom logic written in JavaScript or Python. These
extensions can extend and override a lot of the default behavior of Wolpi, such as:

- Authorization (e.g. to integrate with your organization's [IIIF Authorization Flow API][auth]) implementation
- Resolving, with the option to include image metadata from an external source to avoid hitting the filesystem for `info.json` requests
- Augment the `info.json` response, e.g. to include reference to [additional IIIF services][extra-services]
- Image operations, to implement custom image transformations or filters that go beyond the IIIF Image API specification:
    * Preprocess images before they are processed (e.g. for watermarking)
    * Override/extend cropping, scaling, rotation and quality transformations (e.g. to implement custom smart cropping algorithms or
      image filters)
    * Implement custom output formats or customize the encoding of existing formats

## Installation

Extensions are configured in the `wolpi.yaml` file under the `extensions` key.
Each extension has a description of its source (`path`, `npm`, or `pypi`) and can optionally have a
set of configuration options.

### Local Extensions

Extensions can be loaded from the local file system by providing a `path` to the file or directory
with the extension code. This is the same for both Python and JavaScript extensions.

```yaml linenums="1"
extensions:
  - path: /path/to/helloworld.js
```

### JavaScript Extensions from an npm package registry

Third-party JavaScript extensions can be installed from an npm package registry, either the default
public npm registry or a custom registry. You can also include authentication credentials for
private registries.

```yaml
extensions:
  # A JS extension that is installed from the default npm index
  - npm:
      pkg: "hello-js-package"
      version: "1.0.0"

  # You can also install a JS extension from a custom npm registry
  - npm:
      pkg: "hello-public-npm-package"
      version: "0.1.0"
      index: "https://npm.my-org.tld/repository/npm-public/"

  # For scoped JS packages, you can include authentication credentials for the custom registry
  - npm:
      pkg: "hello-private-npm-package"
      version: "0.3.0"
      index: "https://npm.my-org.tld/repository/npm-private/"
      indexAuth:
        token: <your-npm-token-here>
      # or you can use username/password authentication:
      #   username: <your-username-here>
      #   password: <your-password-here>
```

### Python Extensions from a PyPI package registry

Python extensions can be installed from the default PyPI registry  or a custom registry, with optional
authentication for private registries.

```yaml
extensions:
  # Python package from the default PyPI index
  - pypi:
      pkg: "hello-py-package"
      version: "1.0.0"

  # Python package from a custom PyPI index, with optional authentication
  - pypi:
      pkg: "hello-private-py-package"
      version: "0.1.0"
      index: "https://custom-pypi.example.com/simple"
      # If you use a custom PyPI index that requires authentication, provide
      # the credentials here
      # indexAuth:
      #   username: <your-username-here>
      #   password: <your-password-here>
```

## Configuration

Extensions can have custom configuration options, which can be set in the `config` field of the extension
definition in the `wolpi.yaml` file. The available configuration options depend on the specific extension
and should be documented in the extension's documentation.

```yaml
extensions:
  - npm:
      pkg: "hello-js-package"
      version: "1.0.0"
    config:
      greeting: "Hello, Wolpi!"
```