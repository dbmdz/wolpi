# Using an extension: Resolving images with regular expression patterns

One of the standout features of Wolpi is its extensibility. You can use extensions
to add new features or modify existing behavior to suit your needs. Probably the
most common use case for customizing a IIIF Image API server is to change how
the server resolves incoming requests to the actual source of the image.

In many cases, Wolpi's default behavior of resolving images by their path in a
directory is not enough. For example, you might have standardized identifiers
for images, and the path or URL to the image file is only derived from it.  If
the derivation from the identifier to the source path or URL can be expressed as
a regular expression with a replacement pattern, you can use the
`@mdz/wolpi-pattern-resolver` extension. It allows you to define a set of
regular expressions and replacement patterns in the configuration, and Wolpi
will apply them to incoming requests to resolve the source of the image.

Say your identifiers are standard UUIDs, and the actual image files are stored
in a directory structure where the first two characters of the UUID define the
first-level subdirectory, and the next two characters define the second-level
subdirectory, like this:

- Identifier: `bf46ad54-12ee-4943-9be5-d121d03b8794`
- Path: `/images/bf/46/ad54-12ee-4943-9be5-d121d03b8794`

Then the regular expression pattern to match the identifier would be something like
`^([a-f0-9]{2})([a-f0-9]{2})([a-f0-9-]+)$`, and the replacement pattern
to derive the path would be `/images/$1/$2/$3`.

TODO: Add example configuration and usage instructions.