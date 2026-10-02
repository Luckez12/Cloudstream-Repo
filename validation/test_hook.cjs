const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync('MSM21/src/main/kotlin/com/msm21/extractors.kt','utf8');
const rawHook = process.env.INJECTED_HTML
 ? fs.readFileSync(process.env.INJECTED_HTML,'utf8').split('<script>')[1].split('</script>')[0]
 : source.split('private const val HOOK_JS = """')[1].split('"""')[0].replace(/<\/?script>/g,'');
const hook=rawHook.replace('__MSM_BLOCKED_HOSTS__','["mc.yandex.ru","pixel.morphify.com"]');
function run({body='',heading='',videos=[],sources=[],diagnostics=false,errors=[],readyState='loading',events=[],transition=null}={}){
 const out=[];const handlers={};const docHandlers={};const intervals=[];const context={URL,TextDecoder,window:{addEventListener:(name,fn)=>handlers[name]=fn,msmBridge:{capture:v=>out.push(v)},jwplayer:()=>({getPlaylist:()=>[{sources}]})},document:{readyState,addEventListener:(name,fn)=>docHandlers[name]=fn,baseURI:'https://abyss.msmbot.club/',body:{innerText:body},querySelector:q=>q==='h1, h2'&&heading?{innerText:heading}:null,querySelectorAll:q=>q==='video'?videos:[]},setTimeout:()=>{},setInterval:fn=>intervals.push(fn)};
 vm.runInNewContext(hook.replace('__MSM_ABYSS_DIAGNOSTICS__',String(diagnostics)),context);
 for(const event of events) { if(docHandlers[event]) docHandlers[event](); if(handlers[event]) handlers[event](); }
 if(transition) { transition(context); for(const fn of intervals) fn(); }
 for(const event of errors) if(handlers[event.type]) handlers[event.type](event);
 return out;
}
assert(run({body:'404 Page not found',heading:'Page not found'}).includes('MSM_PAGE_STATE|not_found'));
assert(run({body:'Verify you are human'}).includes('MSM_VERIFY|human_check'));
const virtual='https://storage.googleapis.com/mediastorage/x/y/539709300.mp4#mp4/r2/1/539709300/270532608/480p/h264?maxChunkSize=5242880';
assert(run({videos:[{currentSrc:virtual}]}).includes('MSM_VIDEO|'+virtual));
const captures=run({sources:[{file:'https://example.com/master.m3u8',type:'application/x-mpegurl'},{file:'https://mc.yandex.ru/watch/x?video=movie.mp4'},{file:'blob:bad'}]});
assert(captures.some(v=>v.includes('master.m3u8')));assert(!captures.some(v=>v.includes('yandex')||v.includes('blob:')));
assert(run({body:'FAQ: page not found',sources:[{file:'https://example.com/video.mp4'}]}).some(v=>v.includes('video.mp4')));
console.log('PASS: Byse not-found, human verification, Abyss virtual capture, HLS preservation, tracker/blob rejection, non-error FAQ');

const diag=run({diagnostics:true,videos:[{currentSrc:virtual,readyState:4,networkState:1,paused:true,error:null}]});
assert(diag.includes('MSM_ABYSS_READY'));
assert(diag.includes('MSM_ABYSS_VIDEO|4|1|0|virtual|1'));
assert(!diag.filter(v=>v.startsWith('MSM_ABYSS_')).some(v=>v.includes('http')||v.includes('539709300')));
assert(!run({videos:[{currentSrc:virtual}]}).some(v=>v.startsWith('MSM_ABYSS_')));
assert(run({diagnostics:true}).includes('MSM_ABYSS_VIDEO|0|0|0|none|1'));
assert(run({diagnostics:true,videos:[{currentSrc:'blob:x',readyState:2,networkState:3,paused:false,error:{code:4}}]}).includes('MSM_ABYSS_VIDEO|2|3|4|blob|0'));
const errs=run({diagnostics:true,errors:[{type:'error',target:{tagName:'SCRIPT'}},{type:'error',target:{tagName:'SCRIPT'}},{type:'unhandledrejection'}]});
assert.strictEqual(errs.filter(v=>v==='MSM_ABYSS_ERROR|script_resource').length,1);
assert(errs.includes('MSM_ABYSS_ERROR|promise_rejection'));
console.log('PASS: Abyss-only ready/video/error telemetry, absent/blob/error states, redacted payloads and error deduplication');

// A hook acknowledgment must not imply document load or a ready player.
const beforeLoad=run({diagnostics:true});
assert(beforeLoad.includes('MSM_ABYSS_READY'));
assert(!beforeLoad.includes('MSM_ABYSS_PAGE|loaded'));
assert(beforeLoad.includes('MSM_ABYSS_PLAYER|0|0|0'));
const loaded=run({diagnostics:true,events:['DOMContentLoaded','DOMContentLoaded','load','load']});
assert.strictEqual(loaded.filter(x=>x==='MSM_ABYSS_PAGE|dom').length,1);
assert.strictEqual(loaded.filter(x=>x==='MSM_ABYSS_PAGE|loaded').length,1);
assert(loaded.includes('MSM_ABYSS_PLAYER|0|0|0'));
const alreadyLoaded=run({diagnostics:true,readyState:'complete',events:['DOMContentLoaded','load']});
assert.strictEqual(alreadyLoaded.filter(x=>x==='MSM_ABYSS_PAGE|loaded').length,1);
const delayedVideos=[];
const delayed=run({diagnostics:true,videos:delayedVideos,events:['load'],transition:c=>{
 c.window.SoTrym=()=>{};
 delayedVideos.push({currentSrc:virtual,readyState:4,networkState:1,paused:false});
}});
assert(delayed.includes('MSM_ABYSS_PLAYER|1|1|1'));
assert(delayed.includes('MSM_VIDEO|'+virtual));
const emptyVideo=run({diagnostics:true,videos:[{currentSrc:'',src:'',readyState:0,networkState:0,paused:true}]});
assert(emptyVideo.includes('MSM_ABYSS_PLAYER|0|1|0'));
assert(!run({readyState:'complete',events:['load','DOMContentLoaded']}).some(x=>x.startsWith('MSM_ABYSS_')));
console.log('PASS: load lifecycle versus hook readiness, late player/source discovery, empty-video distinction, stage deduplication and non-Abyss isolation');
