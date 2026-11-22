// Kasane playback verification runtime.
//
// Runs inside the platform browser engine (Android System WebView, WebKitGTK on
// Linux) on an app-owned origin. It performs no network requests: native code
// fetches Google's BotGuard challenge, integrity token and the YouTube player
// script, and passes them in. Results are reported through the host-provided
// `globalThis.__kasaneReply(id, json)` bridge. Nothing is persisted.
import { BotGuardClient, parseChallengeData } from 'bgutils-js/botguard';
import { WebPoMinter } from 'bgutils-js/webpo';
import { JsAnalyzer, JsExtractor, JsMatchers } from 'youtubei.js/web';

const PROTOCOL = 1;
let pendingIntegrity = null;
let minter = null;
let player = null;

function processor() {
  return `return function process(n, sp, s) {
  const url = "https://ytjs.googlevideo.com/videoplayback?expire=0&n=" + encodeURIComponent(n || "");
  const make = exportedVars.nsigFunction;
  if (typeof make !== "function") throw new Error("player-js-unsupported");
  const ctor = make(url, sp || "", s || "");
  const proto = Object.getPrototypeOf(ctor);
  for (const prop of Object.getOwnPropertyNames(proto)) {
    if (["constructor", "clone", "set", "get"].includes(prop)) continue;
    if (typeof ctor[prop] === "function") ctor[prop]();
  }
  const sig = sp ? ctor.get(sp) : null;
  const next = ctor.get("n");
  return { sig: sig ? decodeURIComponent(sig) : null, n: next ? decodeURIComponent(next) : null };
};`;
}

const methods = {
  protocol() {
    return PROTOCOL;
  },

  async attest(createResponse) {
    const challenge = parseChallengeData(createResponse);
    const script = challenge.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
    if (!script) throw new Error('verification-challenge-invalid');
    new Function(script)();
    const client = await BotGuardClient.create({
      program: challenge.program,
      globalName: challenge.globalName,
      globalObject: globalThis,
    });
    const webPoSignalOutput = [];
    const snapshot = await client.snapshot({ webPoSignalOutput });
    pendingIntegrity = webPoSignalOutput;
    minter = null;
    return snapshot;
  },

  async integrity(token) {
    if (!pendingIntegrity) throw new Error('verification-not-started');
    minter = await WebPoMinter.create({ integrityToken: token }, pendingIntegrity);
    return true;
  },

  async mint(binding) {
    if (!minter) throw new Error('verification-not-ready');
    return minter.mintAsWebsafeString(binding);
  },

  loadPlayer(source) {
    const analyzer = new JsAnalyzer(source, {
      extractions: [{ friendlyName: 'nsigFunction', match: JsMatchers.nsigMatcher }],
    });
    const script = new JsExtractor(analyzer).buildScript({ disallowSideEffectInitializers: true });
    if (!script.exported.includes('nsigFunction')) throw new Error('player-js-unsupported');
    player = new Function(`${script.output}\n${processor()}`)();
    return true;
  },

  decipher(n, sp, s) {
    if (!player) throw new Error('player-js-not-loaded');
    return player(n, sp, s);
  },
};

globalThis.KasaneVerifier = {
  run(id, method, args) {
    Promise.resolve()
      .then(() => {
        const fn = methods[method];
        if (!fn) throw new Error('verification-method-unknown');
        return fn(...args);
      })
      .then(
        (value) => globalThis.__kasaneReply(id, JSON.stringify({ ok: true, value })),
        (error) => globalThis.__kasaneReply(id, JSON.stringify({ ok: false, error: String(error && error.message || error) })),
      );
  },
};
