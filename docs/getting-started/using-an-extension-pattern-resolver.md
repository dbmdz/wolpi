# Using an extension: Resolving images with regular expression patterns

One of the standout features of Wolpi is its extensibility. You can use extensions
to add new features or modify existing behavior to suit your needs. Probably the
most common use case for customizing a IIIF Image API server is to change how
the server resolves incoming requests to the actual source of the image.

As we have seen in [the previous tutorial](./serving-your-first-image.md), Wolpi
ships with a very simple resolver by default, that simply treats identifiers as
a relative path in a base directory. While simple, this is often not enough.
For example, you might have standardized identifiers for images, and the path
or URL to the image  file is only derived from it. If  the derivation from the
identifier to the source path or URL can be expressed as  a regular expression
with a replacement pattern, you can use the [`@mdz/wolpi-pattern-resolver`][extension]
extension.
It allows you to define a set of  regular expressions and replacement patterns in
the configuration, and Wolpi  will apply them to incoming requests to resolve the
source of the image.

[extension]: https://github.com/dbmdz/wolpi-pattern-resolver

Say your identifiers are standard UUIDs, and the actual image files are stored
in a directory structure where the first eight characters define a four-level
subdirectory structure under a base directory and the image is stored as a JPEG2000 file under its
full UUID:

- **Identifier**: `bf46ad54-12ee-4943-9be5-d121d03b8794`
- **Directory Structure**:
  ```
  images
  └─ bf
   └─ 46
    └─ ad
     └─ 54
      └─ bf46ad54-12ee-4943-9be5-d121d03b8794.jp2
  ```

Then the regular expression pattern to match the identifier would be something like
`^(([a-f0-9]{2})([a-f0-9]{2})([a-f0-9]{2})([a-f0-9]{2})-[a-f0-9-]+)$`, and the replacement pattern
to derive the path would be `/images/$2/$3/$4/$5/$1.jp2`.

To set this all up with Wolpi, simply create a configuration file:

```yaml
extensions:
  - npm: #(1)!
      pkg: "@mdz/wolpi-pattern-resolver"
      version: "0.1.0"
    config:
      resolvingPatterns: #(2)!
        - pattern: "^(([a-f0-9]{2})([a-f0-9]{2})([a-f0-9]{2})([a-f0-9]{2})-[a-f0-9-]+)$" #(3)!
          substitutions: #(4)!
            - "/images/$2/$3/$4/$5/$1.jp2"
```

1.  The Pattern Resolving extension is a JavaScript extension that is published on
    [npmjs.com](https://npmjs.com). This is not the only possible source, to learn more,
    head over to the [guide on installing extensions](../how-to/install-extensions.md).
2. If you specify multiple pattern and substitution pairs here, the first matching pattern with
   a substitution pointing to a resolvable target will be used as the resolving result
2. Regular Expressions are evaluated according to the [ECMAScript 2025 Standard][ecma-regex],
   the easiest way to test your patterns is by using [Regex101][regex101] and selecting the `JavaScript`
   flavor in the left sidebar
3. If you specify more than one substitution, the extension will attempt all of them and select
   the one that resolves to an existing file.

[ecma-regex]: https://tc39.es/ecma262/2025/#sec-regexp-regular-expression-objects
[regex101]: https://regex101.com/r/53mTzQ/2/substitution

Substitutions do not have to be file paths, you can use HTTP/HTTPS URLs, too!

```yaml
extensions:
  - npm:
      pkg: "@mdz/wolpi-pattern-resolver"
      version: "0.1.0"
    config:
      resolvingPatterns:
        - pattern: "^[a-f0-9-]+$"
          substitutions: #(1)!
            - "https://my.company.tld/imagestore/$1"
```

1. For HTTP substitutions, the extension will make a HEAD request to check if the resource
   is actually available.

If you have resolving needs that go beyond simple pattern matching on strings, you can head over
to the [Extension Development](../extension-development.md) section and learn all about rolling
your own Wolpi extension!