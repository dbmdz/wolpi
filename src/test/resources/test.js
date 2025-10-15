export function info() {
  const System = Java.type("java.lang.System")
  // CHANGE THIS LINE FOR TESTS
  return {
    name: "Test JS File Extension",
    apiVersion: 1,
    description: "A test JS file extension"
  };
}

export function authorize() {
  return true;
}

export function cleanup() {}
