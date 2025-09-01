import fetchSync from 'wolpi:fetch';

export default {
  info: () => ({
    apiVersion: 1,
    name: 'internet-archive-resolver-js',
    description: 'Resolves images from the Internet Archive.'
  }),
  resolve: (identifier) => {
    const metadataUrl = `https://archive.org/metadata/${identifier}`;
    const response = fetchSync(metadataUrl);
    if (!response.ok) {
      return
    }
    const data = response.json();
    if (!data.metadata || data.metadata.mediatype !== 'image') {
      return;
    }
    let bestFile = null;
    for (const file of data.files) {
      if (file.source !== 'original') {
        continue;
      }
      if (file.format === 'JPEG 2000') {
        bestFile = file;
        break;
      }
      if (file.format === 'JPEG') {
        bestFile = file;
      }
    }
    if (!bestFile) {
      return;
    }
    return {
      url: `https://${data.d1}${data.dir}/${bestFile.name}`
    };
  }
}
