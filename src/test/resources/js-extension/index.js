export default {
  info() {
    return {
      name: "Test JS Extension",
      apiVersion: 1,
      description: "A test JS extension"
    }
  },
  cleanup() {
  },
  authorize() {
    return true;
  }
}
