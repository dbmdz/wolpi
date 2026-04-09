"""A simple Wolpi extension that resolves image files by their `py-` prefixed filename in a directory.

The identifier (without prefix) is matched against the filename (without extension) in a specified base
directory. If a match is found for one of the supported image file extensions, the absolute path to the file
is returned.

"""
from pathlib import Path

from wolpi import ExtensionInfo, FilesystemImageSource, config

IMAGE_EXTENSIONS = {'.jpg', '.jpeg', '.png', '.gif', '.jp2', '.tif', '.webp'}

def info() -> ExtensionInfo:
    return {
        'apiVersion': 1,
        'name': 'hello-world-py',
        'description': 'just a simple resolving proof-of-concept'
    }

def cleanup():
    # No cleanup necessary for this simple extension, since we don't keep state
    # between hook invocations
    pass

def resolve(identifier: str, etag: str | None = None, last_modified: str | None = None) -> FilesystemImageSource | None:
    if not identifier.startswith('py-'):
        return
    identifier = identifier[3:]  # Remove 'py-' prefix
    # The `wolpi` module provides access to the Wolpi context, including the configuration for the extensions.
    base_dir = Path(config['baseDirectory'])
    for path in base_dir.iterdir():
        if path.stem == identifier and path.suffix in IMAGE_EXTENSIONS:
            return {'path': str(path.absolute())}
