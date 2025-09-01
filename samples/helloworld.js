/** A very basic proof-of-concept extension that resolves images to a directory defined in the config.
 *
 * The identifiers supported by this extension are filenames without an extension, but with a `js-` prefix.
 * They will be resolved to an image file without the prefix in the directory, if it exists.
 *
 * It must be configured with a `baseDirectory` in the extension configuration.
 */

// We provide a subset of the synchronous node.js 'fs' module here for convenience.
import { readDirSync } from "wolpi:fs";

const IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'gif', 'jp2', 'tif', 'webp']);

export default {
  // Define the extension metadata.
  info: () => ({
    apiVersion: 1,
    name: 'hello-world',
    description: 'just a simple resolving proof-of-concept'
  }),
  // The resolve function is called when an identifier needs to be resolved to an image source
  resolve: (identifier) => {
    if (!identifier.startsWith('js-')) {
      return;
    }
    identifier = identifier.substring(3); // Remove the 'js-' prefix.
    // The `wolpi` global provides access to the Wolpi context, including the configuration for the extensions.
    for (const { parentPath, name } of readDirSync(wolpi.config().baseDirectory)) {
      const basename = name.substring(0, name.lastIndexOf('.'));
      const extension = name.substring(name.lastIndexOf('.') + 1);
      if (basename === identifier && IMAGE_EXTENSIONS.has(extension)) {
        return { path: `${parentPath}/${name}` };
      }
    }
  }
}
