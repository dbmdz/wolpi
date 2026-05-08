# Using Extensions

As was already shown in the [extension tutorial](../getting-started/using-an-extension-pattern-resolver.md),
Wolpi's most powerful feature is the ability to extend its feature set through custom extensions.
This How-To will guide you through the various ways extensions can be installed and configured.


## Installing Extensions
Extensions are configured in the `wolpi.yml` file under the `extensions` key, with each extensions
as a separate list entry, containing a description of its source (`path`, `npm`, or `pypi`) and
optionally a set of configuration options.

!!! warning
    If you run Wolpi outside the [official container](./deploy-using-docker-podman.md), please take note
    of  the [requirements](./deploy-using-the-jar.md) for installing extension packages.

### Local Extensions

The simplest way to install an extension is to download or create it on the filesystem and point
Wolpi towards its location with the `path` key. The target can either by a file or a directory,
and the syntax is identical for both JavaScript and Python extensions:

```yaml linenums="1"
extensions:
  - path: /path/to/helloworld.js
  - path: /path/to/python-package
```

For details of what Wolpi expects in those files or packages, please refer to
the [extension development documentation](../extension-development.md).

### JavaScript Extensions from an npm package repository

The most straight-forward way to install JavaScript-based third-party extensions is to simply point
Wolpi to the corresponding package in an npm package repository, along with its version:

```yaml
extensions:
  - npm:
      pkg: "hello-npmjs-package"
      version: "1.0.0"
```

By default, this will look up the package on [npmjs.com](https://npmjs.com). If your extension
is on an internal package repository, you can specify the address of that repository through the `index` key:

```yaml
extensions:
  - npm:
      pkg: "hello-internal-npm-package"
      version: "0.1.0"
      index: "https://npm.my-org.tld/repository/npm-public/"
```

In case your scoped package is private and needs authentication for access, you can either specify
a `token` or a `username` and `password` in the `indexAuth` section of your extension's configuration:

```yaml
extensions:
  - npm:
      pkg: "@my-org/hello-private-npm-package"
      version: "0.3.0"
      indexAuth:
        token: <your-npm-token-here>
      # if your package is on a custom registry, add:
      # index: "https://npm.my-org.tld/repository/npm-private/"
      # or you can use username/password authentication:
      #   username: <your-username-here>
      #   password: <your-password-here>
```

### Python Extensions from a PyPI package registry

Much like JavaScript extensions, Python extensions can be installed from the public PyPI repository
by specifying the package name in the `pkg` key and their `versions`:

```yaml
extensions:
  # Python package from the default PyPI index
  - pypi:
      pkg: "hello-pypi-package"
      version: "1.0.0"
```

Python extensions can also be installed from a private PyPI repository specified as a URL in the
`index` key, optionally with `username` and `password` in an `indexAuth` section for basic auth
authentication. Make sure that `index` points to the [Simple Repository API][py-repo-api] endpoint
of the  repository, most commonly exposed at the `/simple` path.

```yaml
  - pypi:
      pkg: "hello-private-pypi-package"
      version: "0.1.0"
      index: "https://custom-pypi.example.com/simple"
      # indexAuth:
      #   username: <your-username-here>
      #   password: <your-password-here>
```

[py-repo-api]: https://packaging.python.org/en/latest/specifications/simple-repository-api/

## Configuration

Optionally, extensions can have custom configuration options, which can be set in the `config` field
of the extension definition in the `wolpi.yml` file. The available configuration options depend on
the specific extension and should be listed in the extension's documentation.

```yaml
extensions:
  - npm:
      pkg: "hello-js-package"
      version: "1.0.0"
    config:
      greeting: "Hello, Wolpi!"
```

## Running Wolpi with extensions

Once you have finished configuration your extensions in your `wolpi.yml`, you can launch the
application and should see a log entry about your extension being installed:

```
  Ѱ Ѱ
 (\_/)
ʚ(•.•)ɞ     ~ wolpi ~
 (")(")    0.1.2
14:48:12.094 💬 PyPiInstaller Installing Python extension 'hello-pypi-package:0.1.0' from PyPI
```

## Extension Validation

Wolpi will then continue with **validating** the extension, ensuring that no part of the official
IIIF Image API specification is broken by the extension in the provided configuration. This is going
to take a while and might involve installation of the [IIIF Image API validation test suite][test-suite]
if it is the first time you install an extension:

```
14:48:13.380 💬 ExtensionRegistry Extension 'Extension Name' loaded.
14:48:14.167 💬 ValidatingRunner Starting validation of 1 extension(s) (0 cached, 1 to validate)
14:48:14.167 💬 ValidatingRunner Validating extension 'Pattern Resolver'...
14:48:14.167 💬 ImageApiValidator Running IIIF Image API validation tests with extension 'Extension Name' against localhost:8080...
14:48:43.784 💬 ValidatingRunner Extension validation successful for all 1 registered extension(s).
14:48:43.785 💬 ValidatingRunner Listening on localhost:8080
```

This validation pass will only be run once per extension version (determined by the `version` key
for external extensions, and by a content hash for local extensions), so the next run of the
application should come up much faster.

If you want to pre-install and pre-verify your extension setup, you can launch the application with
the `install-extensions` subcommand, which will perform the above steps and then simply exit. If
you use a container-based deployment, check out the how-to
on [building containers with pre-installed extensions](./build-containers-with-pre-installed-extensions.md).

[test-suite]: https://pypi.org/project/iiif-validator-ng/

## Using your Extension

Now that Wolpi is up and running, you can test your extension by making IIIF Image API requests
to it. Which requests depends on the type of plugin you installed.
