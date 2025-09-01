import fetchSync from 'wolpi:fetch';

export default {
  info: () => ({
    apiVersion: 1,
    name: 'internet-archive-resolver-js',
    description: 'Resolves images from the Internet Archive.'
  }),
  cleanup: () => {
    // No cleanup needed in this example, since we don't keep any state between
    // hook calls
  },
  resolve: (identifier, eTag, lastModified) => {
    const metadataUrl = `https://archive.org/metadata/${identifier}`;
    const headers = {};
    if (eTag) {
      headers['If-None-Match'] = eTag;
    }
    if (lastModified) {
      headers['If-Modified-Since'] = lastModified;
    }
    const response = fetchSync(metadataUrl, { headers });
    if (response.status === 304) {
      return { notModified: true };
    }
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
