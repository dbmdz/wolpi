"""
Python runtime code for Wolpi extensions.

Mostly there to give access to `HttpStatusError` and to match the types from the
`wolpi-extension-api` Python type hint package, i.e. so that the type imports
resolve to something at runtime.
"""
import abc
import sys
import types


class HttpStatusError(Exception):
    """
    Exception to return a HTTP response with a status code, a message and an
    optional JSON body.
    """

    message: str
    status: int
    details: dict | None

    def __init__(self, message: str, status: int, details: dict | None = None):
        self.message = message
        self.status = status
        self.details = details
        super().__init__(message)


# We simulate a nested `.errors` module
error_module = types.ModuleType("wolpi.errors")
error_module.HttpStatusError = HttpStatusError

# We cache mocked placeholder types so they refer to the same thing in the same
# context
_placeholder_types: dict[str, type] = {}


def _make_placeholder_type(name: str) -> type:
    """
    Given a type name, create an empty placeholder type in the 'wolpi' module.
    """
    placeholder = _placeholder_types.get(name)
    if placeholder is None:
        placeholder = types.new_class(name)
        placeholder.__module__ = "wolpi"
        _placeholder_types[name] = placeholder
    return placeholder


class WolpiExtension(abc.ABC):
    """
    Convenience base class for Wolpi Python extensions.

    Does not have any relevance at runtime, and is only there to match the type
    hints from the `wolpi-extension-api` package.

    `info()` and `cleanup()` remain abstract because every extension must
    implement them. The remaining hooks are modeled with permissive default
    implementations so class-based extensions only need to override the hooks
    they use.
    """

    @abc.abstractmethod
    def info(self):
        raise NotImplementedError()

    @abc.abstractmethod
    def cleanup(self):
        raise NotImplementedError()

    def setup(self):
        return None

    def destroy(self):
        return None

    def skippable_hooks(self, request):
        return None

    def authorize(self, identifier, headers, client_ip):
        return True

    def resolve(self, identifier, client_etag, client_last_modified):
        return None

    def augment_info_json(self, identifier, current_info_json, iiif_version):
        return None

    def pre_process_image(self, image, identifier, image_info, request):
        return None

    def pre_scale(self, image, identifier, image_info, request):
        return None

    def pre_crop(self, image, identifier, image_info, request):
        return None

    def pre_rotate(self, image, identifier, image_info, request):
        return None

    def pre_quality(self, image, identifier, image_info, request):
        return None

    def pre_format(self, image, identifier, image_info, request):
        return None


# Build the wolpi module that extensions will be able to import
wolpi_module = types.ModuleType("wolpi")
wolpi_module.__path__ = []
wolpi_module.__doc__ = "Injected Wolpi runtime module for Python extensions."
wolpi_module.WolpiExtension = WolpiExtension
wolpi_module.errors = error_module

# These are re-exports from the Wolpi runtime context in __wolpi_module__
_runtime_exports = {
    "config",
    "wolpiVersion",
    "extensionVersion",
    "logger",
    "metrics",
    "vipsArena",
    "imageRequestParser",
    "httpClient",
    "baseUri",
}


def _wolpi_getattr(name):
    if name in _runtime_exports:
        return getattr(__wolpi_module__, name)
    if name == "HttpStatusError":
        return HttpStatusError
    if name and name[0].isupper():
        return _make_placeholder_type(name)
    raise AttributeError(name)


wolpi_module.__getattr__ = _wolpi_getattr
wolpi_module.__all__ = ["WolpiExtension", "errors", *_runtime_exports]

sys.modules["wolpi"] = wolpi_module
sys.modules["wolpi.errors"] = error_module