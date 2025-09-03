def wolpi_extension():
    return {
        "info": lambda: {"name": "Test PY Extension", "apiVersion": 1, "description": "A test extension written in Python."},
        "cleanup": lambda: None,
        "authorize": lambda: True,
    }
