const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const source = fs.readFileSync(__dirname + '/../MSM21/src/main/kotlin/com/msm21/extractors.kt', 'utf8');
const script = source.match(/private const val QUIET_JS = """([\s\S]*?)"""/)[1];
function realm() {
  const listeners = {}, media = [], observers = [];
  class Media {
    constructor() { this._muted = false; this._volume = 1; }
    play() { this.playedMuted = this.muted && this.volume === 0; return Promise.resolve('played'); }
  }
  for (const key of ['muted', 'volume']) Object.defineProperty(Media.prototype, key, {
    configurable: true, get() { return this['_' + key]; }, set(value) { this['_' + key] = value; }
  });
  class AudioNode {
    constructor(context) { this.context = context; }
    connect(destination, ...ports) { this.lastConnection = [destination, ...ports]; return destination; }
  }
  const context = {
    HTMLMediaElement: Media, AudioNode,
    document: { querySelectorAll: () => media, addEventListener: (event, cb) => listeners[event] = cb },
    MutationObserver: class { constructor(cb) { observers.push(cb); } observe() {} }
  };
  context.window = context;
  vm.createContext(context);
  return {context, Media, AudioNode, listeners, media, observers};
}
(async () => {
  const r = realm(); const existing = new r.Media(); r.media.push(existing);
  vm.runInContext(script, r.context);
  assert(existing.muted && existing.volume === 0 && existing.defaultMuted);
  existing.muted = false; existing.volume = 1;
  assert(existing.muted && existing.volume === 0);
  const fresh = new r.Media();
  assert.equal(await fresh.play(), 'played'); assert(fresh.playedMuted);
  r.media.push(fresh); r.observers[0](); assert(fresh.defaultMuted);
  assert.equal(r.context.open('https://ad.example'), null);
  const audio = {destination: {}, createGain() { const node = new r.AudioNode(this); node.gain = {value: 1}; return node; }};
  const node = new r.AudioNode(audio);
  assert.equal(node.connect(audio.destination), audio.destination);
  assert.equal(node.lastConnection[0], audio.__msmSilentSink);
  assert.equal(audio.__msmSilentSink.gain.value, 0);
  assert.equal(audio.__msmSilentSink.lastConnection[0], audio.destination);
  const internal = new r.AudioNode(audio); assert.equal(node.connect(internal, 1, 0), internal);
  assert.deepEqual(node.lastConnection, [internal, 1, 0]);
  const play = r.Media.prototype.play; vm.runInContext(script, r.context);
  assert.equal(r.Media.prototype.play, play); // reinjection is idempotent
  const native = realm(); const player = new native.Media(); await player.play();
  assert(!player.muted && player.volume === 1); // another realm remains untouched
  console.log('PASS WebView media, WebAudio, popups, reinjection, and realm isolation');
})().catch(error => { console.error(error); process.exitCode = 1; });
