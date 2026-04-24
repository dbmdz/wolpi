"""
Python runtime code for Wolpi extensions.

Mostly there to give access to `HttpStatusError` and to match the types from the
`wolpi-extension-api` Python type hint package, i.e. so that the type imports
resolve to something at runtime.

Also provides a small Python-side helper to determine which hooks are actually
implemented on a given extension object.
"""
import abc
import collections.abc
import inspect
import itertools
import sys
import types

import java

ExtensionHooks = java.type("dev.mdz.wolpi.extension.model.ExtensionHooks")
JavaMap = java.type("java.util.Map")
_SUPPORTED_HOOK_NAMES = frozenset(itertools.chain.from_iterable(
    hook.getValidNames()
    for hook in ExtensionHooks.values()
))

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


def _get_named_member(obj, name: str):
    if isinstance(obj, collections.abc.Mapping):
        return obj.get(name)
    if isinstance(obj, JavaMap):
        return obj.get(name)
    try:
        return obj[name]
    except Exception:
        pass
    try:
        return getattr(obj, name)
    except Exception:
        return None


def _is_callable_member(member):
    return callable(member) or (hasattr(member, "canExecute") and member.canExecute())


def _get_callable_member(obj, name: str):
    member = _get_named_member(obj, name)
    return member if _is_callable_member(member) else None


def _call(member):
    if callable(member):
        return member()
    return member.execute()


def _iter_members(obj):
    if isinstance(obj, types.ModuleType):
        return vars(obj).items()
    if isinstance(obj, collections.abc.Mapping):
        return obj.items()
    if isinstance(obj, JavaMap):
        return ((entry.getKey(), entry.getValue()) for entry in obj.entrySet())
    return None


def __wolpi_discover_hooks__(extension_object) -> set[str]:
    """
    Discover which supported Wolpi hooks are implemented by an extension object.

    Top-level extensions are represented as module/binding dictionaries and
    expose hooks as public callables. Class-based extensions are represented as
    instances and expose hooks as methods. For class instances, inherited hooks
    from user-defined base classes count, but inherited default methods from
    WolpiExtension do not.

    :param extension_object: Extension object to examine
    :returns: Set of supported Wolpi hook names implemented by the object.
    """
    try:
        members = _iter_members(extension_object)
    except Exception:
        members = None

    if members is not None:
        return {
            name
            for name, value in members
            if isinstance(name, str)
            and not name.startswith("_")
            and name in _SUPPORTED_HOOK_NAMES
            and _is_callable_member(value)
        }

    # Class objects are not supported
    if inspect.isclass(extension_object):
        return set()

    # Walk up the type hierarchy and discover hooks among the members. Inherited
    # hooks from user-defined base classes count; defaults from WolpiExtension do not.
    mro = type(extension_object).__mro__
    discovered = set()
    for cls in mro:
        if cls is object:
            continue
        for name, member in cls.__dict__.items():
            if name.startswith("_") or name not in _SUPPORTED_HOOK_NAMES or name in discovered:
                continue
            function = member.__func__ if isinstance(member, (staticmethod, classmethod)) else member
            if not callable(function):
                continue
            if cls is WolpiExtension:
                continue
            discovered.add(name)
    return discovered


def __wolpi_load_extension__(global_bindings, entry_point_name=None, factory_name="wolpi_extension"):
    """
    Resolve the extension object Java should execute and discover its hooks.

    For package entry points, this calls the named entry point and treats its
    return value as the extension object. For single-file extensions, top-level
    hooks win; if none exist, the optional wolpi_extension() factory is called.

    :param global_bindings: Snapshot of the evaluated Python module globals.
    :param entry_point_name: Optional package entry point function name.
    :param factory_name: Optional single-file factory function name.
    :returns: (extension_object, hook_names), where extension_object is the
              value used for hook execution and hook_names is a tuple of
              discovered hook names. The tuple form gives Java a stable
              array-like value to convert into ExtensionHooks.
    """
    if entry_point_name:
        entry_point = _get_callable_member(global_bindings, entry_point_name)
        if entry_point is None:
            raise ValueError(f"Entry point function '{entry_point_name}' not found in extension.")
        extension_object = _call(entry_point)
        return extension_object, tuple(sorted(__wolpi_discover_hooks__(extension_object)))

    top_level_hooks = __wolpi_discover_hooks__(global_bindings)
    if top_level_hooks:
        return global_bindings, tuple(sorted(top_level_hooks))

    factory = _get_callable_member(global_bindings, factory_name)
    if factory is not None:
        extension_object = _call(factory)
        return extension_object, tuple(sorted(__wolpi_discover_hooks__(extension_object)))

    return global_bindings, tuple()


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
wolpi_module.__wolpi_discover_hooks__ = __wolpi_discover_hooks__
wolpi_module.__wolpi_load_extension__ = __wolpi_load_extension__
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
