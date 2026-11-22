// Bundles verifier/entry.js with pinned bgutils-js and youtubei.js into
// verifier/dist/kasane-verifier.js, which Android (assets) and the Linux
// desktop (include_str!) both load into their platform browser engine.
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
await build({
  absWorkingDir: root,
  entryPoints: ['verifier/entry.js'],
  outfile: 'verifier/dist/kasane-verifier.js',
  bundle: true,
  minify: true,
  format: 'iife',
  platform: 'browser',
  target: ['es2020'],
  legalComments: 'eof',
  logLevel: 'info',
});
