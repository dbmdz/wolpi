// A deliberately broken extension: it implements the `authorize` hook, but throws while Wolpi
// initializes it. Loading it must not silently drop the authorization check.
export function info() {
  throw new Error("boom during extension initialization");
}

export function authorize() {
  return false;
}

export function cleanup() {}
