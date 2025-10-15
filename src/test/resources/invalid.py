import java

def info():
    System = java.type("java.lang.System")
    # CHANGE THIS LINE FOR TESTS
    return {"name": "Test PY File Extension", "apiVersion": 1, "description": "A test extension written in Python."}

def authorize(identifier, headers, clientIp):
    return True